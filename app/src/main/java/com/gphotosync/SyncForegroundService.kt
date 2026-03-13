package com.gphotosync
import android.util.Log

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume

class SyncForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.gphotosync.START_SYNC"
        const val ACTION_STOP  = "com.gphotosync.STOP_SYNC"
        const val ACTION_RETRY = "com.gphotosync.RETRY_FAILED"
        const val CHANNEL_ID   = "sync_channel"
        const val NOTIF_ID     = 1001

        var progressCallback: ((SyncProgress) -> Unit)? = null
        var retryItems: List<SyncRecord>? = null
        var logCallback: ((String) -> Unit)? = null
        var currentSessionId: String = ""
        private val createdFolders = mutableSetOf<String>()
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun logToFile(msg: String) {
        try {
            val f = java.io.File(filesDir, "sync_log.txt")
            f.appendText("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())} $msg\n")
        } catch (_: Exception) {}
    }

    private fun liveLog(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        logToFile(msg)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            logCallback?.invoke("[$ts] $msg")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                logToFile("onStartCommand ACTION_START received")
                startForeground(NOTIF_ID, buildNotification("동기화 준비 중...", 0, 0))
                logToFile("About to call startSync")
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
        logToFile("startSync called, isRetry=$isRetry")
        synchronized(createdFolders) { createdFolders.clear() }
        synchronized(createdFolders) { createdFolders.clear() }
        val googleApi   = GooglePhotosApi(this)
        val oneDriveApi = OneDriveApi(this)
        val progressDb  = SyncProgressStore(this)

        syncJob = scope.launch {
            try {
                val synced = progressDb.loadSyncedIds().toMutableSet()
                    logToFile("synced ids loaded: ${synced.size}")

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
                        logToFile("calling fullSync")
                    fullSync(googleApi, oneDriveApi, progressDb, synced)
                }

                withContext(Dispatchers.Main) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
            } catch (e: CancellationException) {
                logToFile("CancellationException: ${e.message}")
                notifyProgress("동기화 중단됨", 0, 0)
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            } catch (e: Exception) {
                logToFile("Exception in startSync: ${e.message}")
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
        logToFile("fullSync - creating picker session")
        notifyProgress("Picker 세션 생성 중...", 0, 0)

        var sessionId: String? = null
        var pickerUri: String? = null
        var sessionDone = false
        val sessionResult = suspendCoroutine<Pair<String?, String?>> { cont ->
            googleApi.createSession { sid, uri -> cont.resume(Pair(sid, uri)) }
        }
        sessionId = sessionResult.first; pickerUri = sessionResult.second

        if (sessionId == null || pickerUri == null) {
            logToFile("fullSync - session creation failed")
            notifyProgress("세션 생성 실패", 0, 0)
            progressCallback?.invoke(SyncProgress(0, 0, 0, true, "세션 생성 실패", 0))
            return
        }

        logToFile("fullSync - session=$sessionId pickerUri=$pickerUri")

        withContext(Dispatchers.Main) {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(pickerUri))
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            applicationContext.startActivity(intent)
        }
        notifyProgress("Google Photos에서 사진을 선택하세요...", 0, 0)

        var mediaReady = false
        while (!mediaReady && scope.isActive) {
            delay(3000)
            mediaReady = suspendCoroutine<Boolean> { cont ->
                googleApi.pollSession(sessionId!!) { ready -> cont.resume(ready) }
            }
            logToFile("fullSync - polling, mediaReady=$mediaReady")
        }

        if (!scope.isActive) return

        notifyProgress("선택된 사진 목록 가져오는 중...", 0, 0)
        liveLog("선택된 사진 목록 가져오는 중...")
        val allItems: List<MediaItem> = suspendCoroutine<List<MediaItem>> { cont ->
            googleApi.listPickedMedia(sessionId!!,
                onDone = { items -> cont.resume(items) },
                onError = { err -> logToFile("listPickedMedia error: $err"); cont.resume(emptyList()) }
            )
        }

        logToFile("fullSync - picked items: ${allItems.size}")
        

        val total = allItems.size
        var done = 0
        var errors = 0
        var skipped = 0
        var doneBytes = 0L
        var totalBytes = 0L

        val syncSessionId = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        currentSessionId = syncSessionId
        progressDb.setTotalCount(total)
        notifyProgress("동기화 시작 (${total}개)", done, total)
        liveLog("동기화 시작: 전체 ${total}개, 기존 동기화 ${synced.size}개")

        for (item in allItems) {
            if (!scope.isActive) break

            if (item.id in synced) {
                done++; skipped++
                liveLog("⏭ 중복 스킵: ${item.filename}")
                progressCallback?.invoke(SyncProgress(done, total, errors, false, null, skipped, totalBytes, doneBytes))
                continue
            }

            val fileData: ByteArray? = suspendCoroutine<ByteArray?> { cont ->
                googleApi.downloadMedia(item) { data -> cont.resume(data) }
            }

            if (fileData == null) {
                errors++; done++
                liveLog("❌ 다운로드 실패: ${item.filename}")
                progressDb.addFailedRecord(SyncRecord(item.id, item.filename, "failed", "다운로드 실패", fileSize = 0, sessionId = syncSessionId))
                progressCallback?.invoke(SyncProgress(done, total, errors, false, null, skipped, totalBytes, doneBytes))
                continue
            }

            val safeData = fileData ?: continue

            val yearFolder = extractYear(item.filename, item.year)
            val folderPath = "${oneDriveApi.rootFolder}/$yearFolder"
            var uploadOk = false

            // 폴더 캐시로 불필요한 ensureFolder 호출 방지
            val folderCached = synchronized(createdFolders) { folderPath in createdFolders }
            val folderOk = if (folderCached) true else {
                val result = suspendCoroutine<Boolean> { cont ->
                    oneDriveApi.ensureFolder(folderPath) { cont.resume(it) }
                }
                if (result) synchronized(createdFolders) { createdFolders.add(folderPath) }
                result
            }

            if (folderOk) {
                val driveId = suspendCoroutine<String?> { cont ->
                    oneDriveApi.uploadFile(safeData, item.filename, folderPath) { cont.resume(it) }
                }
                uploadOk = driveId != null
            }

            if (uploadOk) {
                synced.add(item.id)
                progressDb.saveSyncedId(item.id)
                progressDb.saveSyncedFileSize(item.id, safeData.size.toLong())
                progressDb.addSuccessRecord(SyncRecord(item.id, item.filename, "success", fileSize = safeData.size.toLong(), sessionId = syncSessionId))
                progressDb.removeFailedRecord(item.id)
                liveLog("✅ 완료: ${item.filename} (${String.format("%.1f", safeData.size / 1024.0)}KB)")
                doneBytes += safeData.size.toLong()
                done++
                val pct = if (total > 0) (done * 100 / total) else 0
                notifyProgress("동기화 중 ($pct%) - ${item.filename}", done, total)
                progressCallback?.invoke(SyncProgress(done, total, errors, false, null, skipped, totalBytes, doneBytes))
            } else {
                errors++; done++
                liveLog("❌ 실패: ${item.filename}")
                progressDb.addFailedRecord(SyncRecord(item.id, item.filename, "failed", "업로드 실패", fileSize = safeData?.size?.toLong() ?: 0, sessionId = syncSessionId))
                progressCallback?.invoke(SyncProgress(done, total, errors, false, null, skipped, totalBytes, doneBytes))
            }
            delay(300)
        }

        googleApi.deleteSession(sessionId!!)

        val msg = if (scope.isActive) "완료! 성공:${done - errors - skipped} 스킵:${skipped} 실패:${errors}" else "동기화 중단됨"
        liveLog(msg)
        notifyProgress(msg, done, total)
        progressDb.setDoneCount(done)
        progressCallback?.invoke(SyncProgress(done, total, errors, true, null, skipped, totalBytes, doneBytes, syncSessionId))
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

            val item = MediaItem(record.id, record.filename, "", "", "", false)
            val fileData: ByteArray? = suspendCoroutine<ByteArray?> { cont ->
                googleApi.downloadMedia(item) { data -> cont.resume(data) }
            }

            if (fileData == null) {
                errors++
                done++
                progressDb.addFailedRecord(record.copy(error = "재시도 다운로드 실패", timestamp = System.currentTimeMillis()))
                progressCallback?.invoke(SyncProgress(done, total, errors, false, null, 0))
                continue
            }

            val year = extractYear(record.filename, record.filename.take(4).let { if (it.all { c -> c.isDigit() }) it else "unknown" })
            val folderPath = "${oneDriveApi.rootFolder}/$year"
            var uploadOk = false

            val folderCached = synchronized(createdFolders) { folderPath in createdFolders }
            val folderOk = if (folderCached) true else {
                val result = suspendCoroutine<Boolean> { cont ->
                    oneDriveApi.ensureFolder(folderPath) { cont.resume(it) }
                }
                if (result) synchronized(createdFolders) { createdFolders.add(folderPath) }
                result
            }

            if (folderOk) {
                val driveId = suspendCoroutine<String?> { cont ->
                    oneDriveApi.uploadFile(fileData, record.filename, folderPath) { cont.resume(it) }
                }
                uploadOk = driveId != null
            }

            if (uploadOk) {
                synced.add(record.id)
                progressDb.saveSyncedId(record.id)
                progressDb.saveSyncedFileSize(record.id, fileData.size.toLong())
                progressDb.addSuccessRecord(SyncRecord(record.id, record.filename, "success", fileSize = fileData.size.toLong()))
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
                CHANNEL_ID, "동기화 진행", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Google Photos to OneDrive 동기화 진행 상황" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun extractYear(filename: String, year: String): String {
        val yrPattern = Regex("""((?:19|20)\d{2})""")
        val match = yrPattern.find(filename)
        if (match != null) return match.groupValues[1]
        return if (year != "unknown") year else "unknown"
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
    val skipped: Int = 0,
    val totalBytes: Long = 0L,
    val doneBytes: Long = 0L,
    val sessionId: String = ""
)
