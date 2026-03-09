package com.gphotosync

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class SyncForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.gphotosync.START_SYNC"
        const val ACTION_STOP  = "com.gphotosync.STOP_SYNC"
        const val ACTION_RETRY = "com.gphotosync.RETRY_FAILED"
        const val CHANNEL_ID   = "sync_channel"
        const val NOTIF_ID     = 1001

        var progressCallback: ((SyncProgress) -> Unit)? = null
        var retryItems: List<SyncRecord>? = null
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIF_ID, buildNotification("동기화 준비 중...", 0, 0))
                startSync(false)
            }
            ACTION_RETRY -> {
                startForeground(NOTIF_ID, buildNotification("재시도 준비 중...", 0, 0))
                startSync(true)
            }
            ACTION_STOP -> {
                syncJob?.cancel()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startSync(isRetry: Boolean) {
        val googleApi   = GooglePhotosApi(this)
        val oneDriveApi = OneDriveApi(this)
        val progressDb  = SyncProgressStore(this)

        syncJob = scope.launch {
            try {
                val synced = progressDb.loadSyncedIds().toMutableSet()

                if (isRetry) {
                    val failedItems = retryItems ?: progressDb.getFailedRecords()
                    if (failedItems.isEmpty()) {
                        notifyProgress("재시도할 항목이 없습니다", 0, 0)
                        progressCallback?.invoke(SyncProgress(0, 0, 0, true, null, 0))
                        stopSelf()
                        return@launch
                    }
                    retrySync(googleApi, oneDriveApi, progressDb, failedItems, synced)
                } else {
                    fullSync(googleApi, oneDriveApi, progressDb, synced)
                }

                withContext(Dispatchers.Main) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
            } catch (e: CancellationException) {
                notifyProgress("동기화 중단됨", 0, 0)
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            } catch (e: Exception) {
                progressCallback?.invoke(SyncProgress(0, 0, 0, false, e.message, 0))
                notifyProgress("오류 발생: ${e.message}", 0, 0)
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
    }

    private suspend fun fullSync(
        googleApi: GooglePhotosApi,
        oneDriveApi: OneDriveApi,
        progressDb: SyncProgressStore,
        synced: MutableSet<String>
    ) {
        notifyProgress("미디어 목록 수집 중...", 0, 0)

        val allItems = mutableListOf<MediaItem>()
        var listDone = false
        googleApi.listAllMedia(
            onPage  = { items, count -> allItems.addAll(items); notifyProgress("목록 수집 중... ${count}개", 0, count) },
            onDone  = { _ -> listDone = true },
            onError = { err -> notifyProgress("오류: $err", 0, 0) }
        )
        while (!listDone && scope.isActive) delay(200)

        val total = allItems.size
        var done  = 0
        var errors = 0
        var skipped = 0

        progressDb.setTotalCount(total)
        notifyProgress("동기화 시작 (${total}개)", done, total)

        for (item in allItems) {
            if (!scope.isActive) break

            // 이미 동기화된 항목 체크
            if (item.id in synced) {
                done++
                skipped++
                val pct = if (total > 0) (done * 100 / total) else 0
                progressCallback?.invoke(SyncProgress(done, total, errors, false, null, skipped))
                continue
            }

            // Google Photos 다운로드
            var fileData: ByteArray? = null
            var dlDone = false
            googleApi.downloadMedia(item) { data -> fileData = data; dlDone = true }
            while (!dlDone && scope.isActive) delay(100)

            if (fileData == null) {
                errors++
                progressDb.addFailedRecord(SyncRecord(item.id, item.filename, "failed", "다운로드 실패", fileSize = 0))
                done++
                progressCallback?.invoke(SyncProgress(done, total, errors, false, null, skipped))
                continue
            }

            // 파일 크기가 동일하면 스킵 (이전에 기록된 크기와 비교)
            val prevSize = progressDb.getSyncedFileSize(item.id)
            if (prevSize == fileData!!.size.toLong()) {
                synced.add(item.id)
                progressDb.saveSyncedId(item.id)
                done++
                skipped++
                progressCallback?.invoke(SyncProgress(done, total, errors, false, null, skipped))
                continue
            }

            // OneDrive 업로드
            val folderPath = "${oneDriveApi.rootFolder}/${item.yearMonth}"
            var uploadDone = false
            var uploadOk   = false
            oneDriveApi.ensureFolder(folderPath) { folderOk ->
                if (folderOk) {
                    oneDriveApi.uploadFile(fileData!!, item.filename, folderPath) { ok ->
                        uploadOk = ok
                        uploadDone = true
                    }
                } else {
                    uploadDone = true
                }
            }
            while (!uploadDone && scope.isActive) delay(100)

            if (uploadOk) {
                synced.add(item.id)
                progressDb.saveSyncedId(item.id)
                progressDb.saveSyncedFileSize(item.id, fileData!!.size.toLong())
                progressDb.addSuccessRecord(SyncRecord(item.id, item.filename, "success", fileSize = fileData!!.size.toLong()))
                progressDb.removeFailedRecord(item.id)
                done++
                val pct = if (total > 0) (done * 100 / total) else 0
                notifyProgress("동기화 중 ($pct%) - ${item.filename}", done, total)
                progressCallback?.invoke(SyncProgress(done, total, errors, false, null, skipped))
            } else {
                errors++
                done++
                progressDb.addFailedRecord(SyncRecord(item.id, item.filename, "failed", "업로드 실패", fileSize = fileData?.size?.toLong() ?: 0))
                progressCallback?.invoke(SyncProgress(done, total, errors, false, null, skipped))
            }

            delay(300)
        }

        val msg = if (scope.isActive) "완료! 성공:${done - errors - skipped} 스킵:${skipped} 실패:${errors}" else "동기화 중단됨"
        notifyProgress(msg, done, total)
        progressDb.setDoneCount(done)
        progressCallback?.invoke(SyncProgress(done, total, errors, true, null, skipped))
    }

    private suspend fun retrySync(
        googleApi: GooglePhotosApi,
        oneDriveApi: OneDriveApi,
        progressDb: SyncProgressStore,
        failedItems: List<SyncRecord>,
        synced: MutableSet<String>
    ) {
        val total = failedItems.size
        var done = 0
        var errors = 0

        notifyProgress("재시도 시작 (${total}개)", done, total)

        for (record in failedItems) {
            if (!scope.isActive) break

            var fileData: ByteArray? = null
            var dlDone = false

            val item = MediaItem(record.id, record.filename, "", "", "", false)
            googleApi.downloadMedia(item) { data -> fileData = data; dlDone = true }
            while (!dlDone && scope.isActive) delay(100)

            if (fileData == null) {
                errors++
                done++
                progressDb.addFailedRecord(record.copy(error = "재시도 다운로드 실패", timestamp = System.currentTimeMillis()))
                progressCallback?.invoke(SyncProgress(done, total, errors, false, null, 0))
                continue
            }

            val yearMonth = record.filename.substringBefore("_", "unknown")
            val folderPath = "${oneDriveApi.rootFolder}/$yearMonth"
            var uploadDone = false
            var uploadOk = false
            oneDriveApi.ensureFolder(folderPath) { folderOk ->
                if (folderOk) {
                    oneDriveApi.uploadFile(fileData!!, record.filename, folderPath) { ok ->
                        uploadOk = ok
                        uploadDone = true
                    }
                } else {
                    uploadDone = true
                }
            }
            while (!uploadDone && scope.isActive) delay(100)

            if (uploadOk) {
                synced.add(record.id)
                progressDb.saveSyncedId(record.id)
                progressDb.saveSyncedFileSize(record.id, fileData!!.size.toLong())
                progressDb.addSuccessRecord(SyncRecord(record.id, record.filename, "success", fileSize = fileData!!.size.toLong()))
                progressDb.removeFailedRecord(record.id)
                done++
                notifyProgress("재시도 중 ($done/$total) - ${record.filename}", done, total)
                progressCallback?.invoke(SyncProgress(done, total, errors, false, null, 0))
            } else {
                errors++
                done++
                progressDb.addFailedRecord(record.copy(error = "재시도 업로드 실패", timestamp = System.currentTimeMillis()))
                progressCallback?.invoke(SyncProgress(done, total, errors, false, null, 0))
            }

            delay(300)
        }

        val msg = "재시도 완료! 성공:${done - errors} 실패:${errors}"
        notifyProgress(msg, done, total)
        progressCallback?.invoke(SyncProgress(done, total, errors, true, null, 0))
    }

    private fun notifyProgress(message: String, done: Int, total: Int) {
        val notif = buildNotification(message, done, total)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, notif)
    }

    private fun buildNotification(message: String, done: Int, total: Int): Notification {
        val intent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, SyncForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("📷 Google Photos → OneDrive")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentIntent(intent)
            .addAction(android.R.drawable.ic_media_pause, "중단", stopIntent)
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "동기화 진행", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Google Photos to OneDrive 동기화 진행 상황" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        syncJob?.cancel()
        scope.cancel()
    }
}

data class SyncProgress(
    val done: Int,
    val total: Int,
    val errors: Int,
    val finished: Boolean,
    val errorMessage: String?,
    val skipped: Int = 0
)
