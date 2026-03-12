package com.gphotosync

import android.app.*
import kotlinx.coroutines.sync.Mutex
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume


class TakeoutUploadService : Service() {

    companion object {
        const val ACTION_START = "com.gphotosync.TAKEOUT_START"
        const val ACTION_STOP = "com.gphotosync.TAKEOUT_STOP"
        const val ACTION_ANALYZE = "com.gphotosync.TAKEOUT_ANALYZE"
        const val ACTION_RESUME = "com.gphotosync.TAKEOUT_RESUME"
        const val ACTION_ANALYZE_RESUME = "com.gphotosync.TAKEOUT_ANALYZE_RESUME"
        const val ACTION_ORGANIZE_ALBUMS = "com.gphotosync.TAKEOUT_ORGANIZE_ALBUMS"
        const val ACTION_MIGRATE = "com.gphotosync.TAKEOUT_MIGRATE"
        const val EXTRA_ZIP_URI = "zip_uri"
        const val CHANNEL_ID = "takeout_channel"
        const val NOTIF_ID = 2001

        var progressCallback: ((TakeoutProgress) -> Unit)? = null
        var logCallback: ((String) -> Unit)? = null
        var refreshLogCallback: (() -> Unit)? = null
        val logBuffer = mutableListOf<String>()
        private const val LOG_BUFFER_MAX = 200
        var analyzeCallback: ((Int, Long, Int) -> Unit)? = null
        var organizeCallback: ((Int, Int, Int) -> Unit)? = null
        var migrateCallback: ((Int, Int, Int) -> Unit)? = null
        var authExpiredCallback: (() -> Unit)? = null
        var skipOneDriveCheck: Boolean = true
        @Volatile var isRunning: Boolean = false
        @Volatile var currentProgress: TakeoutProgress? = null
        @Volatile var uploadStartTime: Long = 0L
        @Volatile var actualUploadStartTime: Long = 0L
    }

    internal val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    internal var job: Job? = null
    internal val jsonDateMap = java.util.concurrent.ConcurrentHashMap<String, String>()
    internal val folderLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    internal val createdFolders = mutableSetOf<String>()
    internal val failedFolders = mutableSetOf<String>()
    internal val largeFileMutex = Mutex()

    // === OkHttp 콜백 → suspend 변환 ===
    internal suspend fun ensureFolderSuspend(api: OneDriveApi, path: String): Boolean {
        for (attempt in 1..3) {
            val ok = suspendCoroutine<Boolean> { cont -> api.ensureFolder(path) { cont.resume(it) } }
            if (ok) return true
            if (attempt < 3) {
                liveLog("⚠ 폴더 생성 재시도 $attempt/3: $path")
                kotlinx.coroutines.delay(2000L * attempt)
            }
        }
        liveLog("❌ 폴더 생성 3회 실패: $path")
        return false
    }

    internal suspend fun uploadFileSuspend(api: OneDriveApi, data: ByteArray, fn: String, fp: String): String? =
        suspendCoroutine { cont -> api.uploadFile(data, fn, fp) { cont.resume(it) } }

    internal suspend fun uploadFileFromFileSuspend(api: OneDriveApi, file: java.io.File, fn: String, fp: String): String? =
        suspendCoroutine { cont -> api.uploadFileFromFile(file, fn, fp) { cont.resume(it) } }

    internal suspend fun getItemIdSuspend(api: OneDriveApi, path: String): String? =
        suspendCoroutine { cont -> api.getItemId(path) { cont.resume(it) } }

    internal suspend fun createAlbumSuspend(api: OneDriveApi, name: String, ids: List<String>): Boolean =
        suspendCoroutine { cont -> api.createAlbum(name, ids) { cont.resume(it) } }

    internal suspend fun moveFileSuspend(api: OneDriveApi, itemId: String, destFolderId: String): Boolean =
        suspendCoroutine { cont -> api.moveFile(itemId, destFolderId) { cont.resume(it) } }

    internal suspend fun listChildrenSuspend(api: OneDriveApi, folderId: String): List<Triple<String, String, Boolean>>? =
        suspendCoroutine { cont -> api.listChildren(folderId) { cont.resume(it) } }

    internal suspend fun deleteItemSuspend(api: OneDriveApi, itemId: String): Boolean =
        suspendCoroutine { cont -> api.deleteItem(itemId) { cont.resume(it) } }

    internal suspend fun getFolderIdSuspend(api: OneDriveApi, path: String): String? =
        suspendCoroutine { cont -> api.getFolderId(path) { cont.resume(it) } }

    internal suspend fun checkFileExistsSuspend(api: OneDriveApi, path: String): Long? =
        suspendCoroutine { cont -> api.checkFileExists(path) { cont.resume(it) } }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); createNotificationChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val uriStr = intent.getStringExtra(EXTRA_ZIP_URI) ?: return START_NOT_STICKY
                startForeground(NOTIF_ID, buildNotification("Takeout 업로드 준비 중...", 0, 0))
                startUpload(Uri.parse(uriStr), intent.getStringExtra("start_date"), intent.getStringExtra("end_date"))
            }
            ACTION_STOP -> {
                if (!TakeoutUploadService.isRunning) { stopSelf(); return START_NOT_STICKY }
                TakeoutUploadService.isRunning = false
                job?.cancel()
                liveLog("업로드 중단 요청...")
            }
            ACTION_RESUME -> {
                val uriStr = intent.getStringExtra(EXTRA_ZIP_URI) ?: return START_NOT_STICKY
                startForeground(NOTIF_ID, buildNotification("Takeout 이어하기...", 0, 0))
                startUpload(Uri.parse(uriStr), intent.getStringExtra("start_date"), intent.getStringExtra("end_date"), resume = true)
            }
            ACTION_ANALYZE -> {
                val uriStr = intent.getStringExtra(EXTRA_ZIP_URI) ?: return START_NOT_STICKY
                startForeground(NOTIF_ID, buildNotification("ZIP 분석 중...", 0, 0))
                analyzeZip(Uri.parse(uriStr), intent.getStringExtra("start_date"), intent.getStringExtra("end_date"), false)
            }
            ACTION_ANALYZE_RESUME -> {
                val uriStr = intent.getStringExtra(EXTRA_ZIP_URI) ?: return START_NOT_STICKY
                startForeground(NOTIF_ID, buildNotification("ZIP 분석 이어하기...", 0, 0))
                analyzeZip(Uri.parse(uriStr), intent.getStringExtra("start_date"), intent.getStringExtra("end_date"), true)
            }
            ACTION_ORGANIZE_ALBUMS -> {
                startForeground(NOTIF_ID, buildNotification("앨범 정리 중...", 0, 0))
                organizeAlbums()
            }
            ACTION_MIGRATE -> {
                startForeground(NOTIF_ID, buildNotification("폴더 정리 중...", 0, 0))
                migrateMonthToYear()
            }
        }
        return START_REDELIVER_INTENT
    }

    // === 진행 상태 저장/로드 ===
    internal fun saveAnalyzeState(sc: Int, mc: Int, ts: Long, jc: Int) {
        getSharedPreferences("takeout_analyze", MODE_PRIVATE).edit()
            .putInt("sc", sc).putInt("mc", mc).putLong("ts", ts).putInt("jc", jc).apply()
    }
    internal fun saveMediaList(names: Set<String>) {
        getSharedPreferences("takeout_media_list", MODE_PRIVATE).edit()
            .putStringSet("names", names).apply()
    }
    internal fun loadMediaList(): Set<String> {
        return getSharedPreferences("takeout_media_list", MODE_PRIVATE)
            .getStringSet("names", emptySet()) ?: emptySet()
    }
    internal fun clearMediaList() {
        getSharedPreferences("takeout_media_list", MODE_PRIVATE).edit().clear().apply()
    }
    internal fun loadAnalyzeState(): LongArray {
        val p = getSharedPreferences("takeout_analyze", MODE_PRIVATE)
        return longArrayOf(p.getInt("sc",0).toLong(), p.getInt("mc",0).toLong(), p.getLong("ts",0L), p.getInt("jc",0).toLong())
    }
    internal fun clearAnalyzeState() { getSharedPreferences("takeout_analyze", MODE_PRIVATE).edit().clear().apply() }

    internal fun saveJsonDateMap() {
        val p = getSharedPreferences("takeout_json_map", MODE_PRIVATE).edit().clear()
        p.putInt("c", jsonDateMap.size)
        var i = 0
        for ((k, v) in jsonDateMap) { p.putString("k$i", k).putString("v$i", v); i++ }
        p.apply()
    }
    internal fun loadJsonDateMap() {
        val p = getSharedPreferences("takeout_json_map", MODE_PRIVATE)
        jsonDateMap.clear()
        for (i in 0 until p.getInt("c", 0)) {
            val k = p.getString("k$i", null) ?: continue
            val v = p.getString("v$i", null) ?: continue
            jsonDateMap[k] = v.substringBefore("/")
        }
    }

    internal fun getUploadedFiles(): MutableSet<String> =
        getSharedPreferences("takeout_progress", MODE_PRIVATE).getStringSet("uf", emptySet())?.toMutableSet() ?: mutableSetOf()
    private var pendingUploaded = mutableSetOf<String>()

    @Synchronized
    internal fun addUploadedFile(name: String) {
        pendingUploaded.add(name)
        if (pendingUploaded.size >= 5) flushUploadedFiles()
    }

    @Synchronized
    internal fun flushUploadedFiles() {
        if (pendingUploaded.isEmpty()) return
        val p = getSharedPreferences("takeout_progress", MODE_PRIVATE)
        val s = p.getStringSet("uf", emptySet())?.toMutableSet() ?: mutableSetOf()
        s.addAll(pendingUploaded)
        p.edit().putStringSet("uf", s).apply()
        pendingUploaded.clear()
    }
    private fun clearUploadedFiles() { getSharedPreferences("takeout_progress", MODE_PRIVATE).edit().clear().apply() }

    private val pendingDriveIds = mutableMapOf<String, String>()

    @Synchronized
    internal fun saveDriveItemId(filename: String, driveId: String) {
        pendingDriveIds[filename] = driveId
        if (pendingDriveIds.size >= 10) flushDriveItemIds()
    }

    @Synchronized
    internal fun flushDriveItemIds() {
        if (pendingDriveIds.isEmpty()) return
        val p = getSharedPreferences("takeout_drive_ids", MODE_PRIVATE).edit()
        for ((k, v) in pendingDriveIds) p.putString(k, v)
        p.apply()
        pendingDriveIds.clear()
    }
    fun loadDriveItemIds(): Map<String, String> {
        val p = getSharedPreferences("takeout_drive_ids", MODE_PRIVATE)
        return p.all.mapNotNull { (k, v) -> if (v is String) k to v else null }.toMap()
    }
    private fun clearDriveItemIds() { getSharedPreferences("takeout_drive_ids", MODE_PRIVATE).edit().clear().apply() }

    internal fun saveAlbumMap(map: Map<String, String>) {
        val p = getSharedPreferences("takeout_album_map", MODE_PRIVATE).edit().clear()
        p.putInt("c", map.size)
        var i = 0
        for ((k, v) in map) { p.putString("k$i", k).putString("v$i", v); i++ }
        p.apply()
    }

    fun loadAlbumMap(): Map<String, String> {
        val p = getSharedPreferences("takeout_album_map", MODE_PRIVATE)
        val map = mutableMapOf<String, String>()
        for (i in 0 until p.getInt("c", 0)) {
            val k = p.getString("k$i", null) ?: continue
            val v = p.getString("v$i", null) ?: continue
            map[k] = v
        }
        return map
    }

    private var logOutputStream: java.io.OutputStream? = null
    private var logWriter: java.io.BufferedWriter? = null
    private fun getLogWriter(): java.io.BufferedWriter? {
        if (logWriter == null) {
            try {
                val resolver = contentResolver
                val collection = android.provider.MediaStore.Downloads.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val cursor = resolver.query(collection,
                    arrayOf(android.provider.MediaStore.Downloads._ID),
                    "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ?",
                    arrayOf("sync_log.txt"), null)
                val uri = if (cursor != null && cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    cursor.close()
                    android.content.ContentUris.withAppendedId(collection, id)
                } else {
                    cursor?.close()
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "sync_log.txt")
                        put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                        put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                    }
                    resolver.insert(collection, values)
                }
                if (uri != null) {
                    logOutputStream = resolver.openOutputStream(uri, "wa")
                    logWriter = java.io.BufferedWriter(java.io.OutputStreamWriter(logOutputStream!!), 8192)
                }
            } catch (e: Exception) { android.util.Log.e("TakeoutUpload", "logWriter init fail: ${e.message}") }
        }
        return logWriter
    }

    @Synchronized
    internal fun liveLog(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        try { getLogWriter()?.apply { write("$ts $msg"); newLine(); flush() } } catch (_: Exception) {}
        val entry = "[$ts] $msg"
        synchronized(logBuffer) { logBuffer.add(entry); if (logBuffer.size > LOG_BUFFER_MAX) logBuffer.removeAt(0) }
        android.os.Handler(android.os.Looper.getMainLooper()).post { logCallback?.invoke("[$ts] $msg") }
    }


    private val workerFileIndex = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    @Synchronized
    internal fun fileLog(workerId: Int, msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        try { getLogWriter()?.apply { write("$ts [W$workerId] $msg"); newLine(); flush() } } catch (_: Exception) {}
        val entry = "[$ts] [W$workerId] $msg"
        synchronized(logBuffer) {
            val idx = workerFileIndex[workerId]
            if (idx != null && idx < logBuffer.size && logBuffer[idx].contains("⏳")) {
                logBuffer[idx] = entry
            } else {
                logBuffer.add(entry)
                workerFileIndex[workerId] = logBuffer.size - 1
                if (logBuffer.size > LOG_BUFFER_MAX) {
                    logBuffer.removeAt(0)
                    for (k in workerFileIndex.keys) { workerFileIndex[k]?.let { if (it > 0) workerFileIndex[k] = it - 1 } }
                }
            }
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post { refreshLogCallback?.invoke() }
    }
    // === Notification ===
    private var lastNotifyTime = 0L
    @Synchronized
    internal fun notifyProgress(msg: String, done: Int, total: Int) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyTime < 500 && done < total && done > 0) return
        lastNotifyTime = now
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification(msg, done, total))
    }

    internal fun buildDoneNotification(msg: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_TAB", 3)
        }
        val openPi = PendingIntent.getActivity(this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("📦 Takeout → OneDrive")
            .setContentText(msg)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentIntent(openPi)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
    }

    private fun buildNotification(msg: String, done: Int, total: Int): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("OPEN_TAB", 3)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val si = PendingIntent.getService(this, 1,
            Intent(this, TakeoutUploadService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("📦 Takeout → OneDrive")
            .setContentText(msg)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_media_pause, "중단", si)
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Takeout 업로드", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Google Takeout → OneDrive 업로드" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        android.util.Log.w("TakeoutUpload", "onDestroy called - service killed")
        super.onDestroy()
        try { logWriter?.close(); logOutputStream?.close() } catch (_: Exception) {}
        job?.cancel()
        scope.cancel()
    }
}
