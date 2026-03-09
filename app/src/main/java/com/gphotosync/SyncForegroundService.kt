package com.gphotosync

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/**
 * 포어그라운드 동기화 서비스
 * 백그라운드에서 앱이 꺼져도 동기화 계속 실행
 */
class SyncForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.gphotosync.START_SYNC"
        const val ACTION_STOP  = "com.gphotosync.STOP_SYNC"
        const val CHANNEL_ID   = "sync_channel"
        const val NOTIF_ID     = 1001

        // ViewModel/MainActivity 와 통신용 콜백
        var progressCallback: ((SyncProgress) -> Unit)? = null
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
                startSync()
            }
            ACTION_STOP -> {
                syncJob?.cancel()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startSync() {
        val googleApi  = GooglePhotosApi(this)
        val oneDriveApi = OneDriveApi(this)
        val progressDb  = SyncProgressStore(this)

        syncJob = scope.launch {
            try {
                // 1. 완료된 항목 로드
                val synced = progressDb.loadSyncedIds().toMutableSet()
                notifyProgress("미디어 목록 수집 중...", 0, 0)

                // 2. Google Photos 목록 수집
                val allItems = mutableListOf<MediaItem>()
                var listDone = false
                googleApi.listAllMedia(
                    onPage  = { items, count -> allItems.addAll(items); notifyProgress("목록 수집 중... $count개", 0, count) },
                    onDone  = { _ -> listDone = true },
                    onError = { err -> notifyProgress("오류: $err", 0, 0) }
                )
                while (!listDone && isActive) delay(200)

                val remaining = allItems.filter { it.id !in synced }
                val total     = allItems.size
                var done      = synced.size
                var errors    = 0

                notifyProgress("동기화 시작 (${remaining.size}개 남음)", done, total)

                // 3. 각 항목 처리
                for (item in remaining) {
                    if (!isActive) break

                    // Google Photos 다운로드
                    var fileData: ByteArray? = null
                    var dlDone = false
                    googleApi.downloadMedia(item) { data -> fileData = data; dlDone = true }
                    while (!dlDone && isActive) delay(100)

                    if (fileData == null) { errors++; continue }

                    // OneDrive 폴더 경로 결정
                    val folderPath = "${oneDriveApi.rootFolder}/${item.yearMonth}"

                    // 폴더 생성 확인 후 업로드
                    var uploadDone = false
                    var uploadOk   = false
                    oneDriveApi.uploadFile(fileData!!, item.filename, folderPath) { ok ->
                        uploadOk   = ok
                        uploadDone = true
                    }
                    while (!uploadDone && isActive) delay(100)

                    if (uploadOk) {
                        synced.add(item.id)
                        done++
                        progressDb.saveSyncedId(item.id)
                        val pct = if (total > 0) (done * 100 / total) else 0
                        notifyProgress("동기화 중 ($pct%) - ${item.filename}", done, total)
                        progressCallback?.invoke(SyncProgress(done, total, errors, false, null))
                    } else {
                        errors++
                    }

                    delay(300) // API 레이트 리밋 방지
                }

                // 4. 완료
                val msg = if (isActive) "동기화 완료! $done개 성공, $errors개 오류" else "동기화 중단됨"
                notifyProgress(msg, done, total)
                progressCallback?.invoke(SyncProgress(done, total, errors, true, null))

                withContext(Dispatchers.Main) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                }

            } catch (e: CancellationException) {
                notifyProgress("동기화 중단됨", 0, 0)
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            } catch (e: Exception) {
                progressCallback?.invoke(SyncProgress(0, 0, 0, false, e.message))
                notifyProgress("오류 발생: ${e.message}", 0, 0)
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
    }

    private fun notifyProgress(message: String, done: Int, total: Int) {
        val notif = buildNotification(message, done, total)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, notif)
    }

    private fun buildNotification(message: String, done: Int, total: Int): Notification {
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
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
                CHANNEL_ID, "동기화 진행",
                NotificationManager.IMPORTANCE_LOW
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
    val errorMessage: String?
)
