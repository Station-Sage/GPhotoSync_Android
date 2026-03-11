package com.gphotosync

import android.app.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.Collections
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream


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
        var updateLogCallback: ((Int, String) -> Unit)? = null
        val logBuffer = mutableListOf<String>()
        private const val LOG_BUFFER_MAX = 200
        var analyzeCallback: ((Int, Long, Int) -> Unit)? = null
        var organizeCallback: ((Int, Int, Int) -> Unit)? = null
        var migrateCallback: ((Int, Int, Int) -> Unit)? = null
        var authExpiredCallback: (() -> Unit)? = null
        var skipOneDriveCheck: Boolean = false
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
        return false
    }

    internal suspend fun uploadFileSuspend(api: OneDriveApi, data: ByteArray, fn: String, fp: String): String? =
        suspendCoroutine { cont -> api.uploadFile(data, fn, fp) { cont.resume(it) } }

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
            ACTION_STOP -> { job?.cancel(); stopSelf() }
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
    private var lastFlushTime = 0L

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

    private var logWriter: java.io.BufferedWriter? = null
    private fun getLogWriter(): java.io.BufferedWriter? {
        if (logWriter == null) {
            try {
                val f = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "sync_log.txt")
                logWriter = java.io.BufferedWriter(java.io.FileWriter(f, true), 8192)
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

    private val workerLogIndex = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    @Synchronized
    internal fun updateWorkerLog(workerId: Int, msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        try { getLogWriter()?.apply { write("$ts $msg"); newLine(); flush() } } catch (_: Exception) {}
        val entry = "[$ts] $msg"
        synchronized(logBuffer) {
            val idx = workerLogIndex[workerId]
            if (idx != null && idx < logBuffer.size) {
                logBuffer[idx] = entry
            } else {
                logBuffer.add(entry)
                workerLogIndex[workerId] = logBuffer.size - 1
                if (logBuffer.size > LOG_BUFFER_MAX) { logBuffer.removeAt(0); workerLogIndex.keys.forEach { k -> workerLogIndex[k]?.let { workerLogIndex[k] = it - 1 } } }
            }
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post { updateLogCallback?.invoke(workerId, entry) }
    }

    // ======== 업로드 ========
    private fun startUpload(zipUri: Uri, startDate: String? = null, endDate: String? = null, resume: Boolean = false) {
        val api = OneDriveApi(this)
        job = scope.launch {
            try {
                if (jsonDateMap.isEmpty()) loadJsonDateMap()
                if (jsonDateMap.isEmpty()) {
                    liveLog("JSON 메타데이터 수집 중...")
                    notifyProgress("메타데이터 수집 중...", 0, 0)
                    val cs2 = contentResolver.openInputStream(zipUri)
                    val zis2 = ZipArchiveInputStream(cs2)
                    var je = zis2.nextZipEntry; var jc = 0
                    while (je != null && isActive) {
                        if (!je.isDirectory && je.name.endsWith(".json")) {
                            val js = readJsonSafe(zis2)
                            if (js != null) { parseJsonMeta(js)?.let { jsonDateMap[it.first] = it.second; jc++ } }
                        } else { drainZipEntry(zis2) }
                        je = zis2.nextZipEntry
                    }
                    zis2.close()
                    saveJsonDateMap()
                    liveLog("JSON ${jc}개 수집 완료")
                }

                liveLog("미디어 파일 목록 로드 중...")
                var mediaNames = loadMediaList()
                if (mediaNames.isEmpty()) {
                    liveLog("저장된 목록 없음. ZIP에서 목록 수집 중...")
                    val tempNames = mutableSetOf<String>()
                    val zis3 = ZipArchiveInputStream(contentResolver.openInputStream(zipUri))
                    var e3 = zis3.nextZipEntry
                    while (e3 != null && isActive) {
                        if (!e3.isDirectory && isMediaFile(e3.name)) {
                            val fn = e3.name.substringAfterLast('/')
                            if (inRange(dateFromName(fn, e3.name), startDate, endDate)) tempNames.add(e3.name)
                        }
                        drainZipEntry(zis3)
                        e3 = zis3.nextZipEntry
                    }
                    zis3.close()
                    mediaNames = tempNames
                    saveMediaList(mediaNames)
                }

                val total = mediaNames.size
                if (total == 0) {
                    liveLog("미디어 파일 없음"); progressCallback?.invoke(TakeoutProgress(0,0,0,true,"미디어 없음"))
                    stopSelf(); return@launch
                }

                isRunning = true
                liveLog("🔑 MS 인증 확인 중...")
                val tokenOk = suspendCoroutine<Boolean> { cont ->
                    com.gphotosync.TokenManager.getValidMicrosoftToken(api.client) { token ->
                        if (token != null) {
                            liveLog("✅ MS 인증 유효 (토큰: ${token.take(10)}...)")
                            cont.resume(true)
                        } else {
                            liveLog("❌ MS 인증 만료 - 인증 탭에서 재로그인 필요")
                            cont.resume(false)
                        }
                    }
                }
                if (!tokenOk) {
                    liveLog("⛔ 업로드 중단: MS 인증이 만료되었습니다. 인증 탭에서 Microsoft 재로그인 후 다시 시도하세요.")
                    notifyProgress("❌ MS 인증 만료 - 재로그인 필요", 0, 0)
                    android.os.Handler(android.os.Looper.getMainLooper()).post { authExpiredCallback?.invoke() }
                    isRunning = false
                    return@launch
                }
                val rootOk = ensureFolderSuspend(api, api.rootFolder)
                if (!rootOk) {
                    liveLog("❌ 루트 폴더 생성 실패: ${api.rootFolder}")
                    return@launch
                }
                synchronized(createdFolders) {
                    val rp = api.rootFolder.split("/")
                    for (i in 1..rp.size) createdFolders.add(rp.take(i).joinToString("/"))
                }
                liveLog("✅ 루트 폴더 준비 완료: ${api.rootFolder}")

                try {
                    val children = suspendCoroutine<List<Triple<String, String, Boolean>>> { cont ->
                        api.listChildren(api.rootFolder) { cont.resume(it ?: emptyList()) }
                    }
                    val yearFolders = children.filter { it.third }.map { it.second }
                    synchronized(createdFolders) {
                        yearFolders.forEach { name -> createdFolders.add("${api.rootFolder}/$name") }
                    }
                    liveLog("📂 기존 폴더 ${yearFolders.size}개 캐싱: ${yearFolders.take(5).joinToString()}...")
                } catch (e: Exception) {
                    liveLog("⚠ 폴더 캐싱 실패 (계속 진행): ${e.message}")
                }

                actualUploadStartTime = 0L
                uploadStartTime = System.currentTimeMillis()
                liveLog("미디어 ${total}개. 업로드 시작...")
                notifyProgress("업로드 시작 (${total}개)", 0, total)
                currentProgress = TakeoutProgress(0, total, 0, false, null, 0, 0)
                progressCallback?.invoke(TakeoutProgress(0, total, 0, false, null))

                val uploaded: MutableSet<String>
                if (resume) {
                    uploaded = Collections.synchronizedSet(getUploadedFiles())
                    if (uploaded.isNotEmpty()) liveLog("이어하기: ${uploaded.size}개 스킵")
                } else {
                    uploaded = Collections.synchronizedSet(getUploadedFiles())
                    if (uploaded.isNotEmpty()) liveLog("기존 완료 ${uploaded.size}개는 스킵합니다")
                }

                val aDone = AtomicInteger(0); val aErrors = AtomicInteger(0)
                val aSkipped = AtomicInteger(0); val aDoneBytes = AtomicLong(0L)
                val lock = Any()

                val channel = Channel<UploadItem>(8)

                val producer = launch {
                    val zis4 = ZipArchiveInputStream(java.io.BufferedInputStream(contentResolver.openInputStream(zipUri), 524288))
                    var e4 = zis4.nextZipEntry
                    try {
                        while (e4 != null && isActive) {
                            if (!e4.isDirectory && e4.name in mediaNames) {
                                val fn = e4.name.substringAfterLast('/')
                                val ym = yearOnly(fn, e4.name, jsonDateMap)
                                val fp = "${api.rootFolder}/$ym"

                                if (e4.name in uploaded) {
                                    if (resume) {
                                        if (skipOneDriveCheck) {
                                            val d = aDone.incrementAndGet()
                                            val s = aSkipped.incrementAndGet()
                                            if (s % 50 == 0 || s == 1) {
                                                val pct = if (total > 0) d * 100 / total else 0
                                                notifyProgress("스킵 중 ($pct%) ${s}개 완료", d, total)
                                                progressCallback?.invoke(TakeoutProgress(d, total, aErrors.get(), false, null, aDoneBytes.get(), s))
                                            }
                                            drainZipEntry(zis4); e4 = zis4.nextZipEntry; continue
                                        }
                                        val checkPath = "$fp/$fn"
                                        val exists = checkFileExistsSuspend(api, checkPath)
                                        if (exists == null) {
                                            synchronized(lock) { uploaded.remove(e4.name) }
                                            liveLog("🔄 재업로드 필요: $fn (OneDrive에서 삭제됨)")
                                        } else {
                                            val d = aDone.incrementAndGet()
                                            val s = aSkipped.incrementAndGet()
                                            if (s % 50 == 0 || s == 1) {
                                                val pct = if (total > 0) d * 100 / total else 0
                                                notifyProgress("스킵 중 ($pct%) ${s}개 완료", d, total)
                                                progressCallback?.invoke(TakeoutProgress(d, total, aErrors.get(), false, null, aDoneBytes.get(), s))
                                            }
                                            drainZipEntry(zis4); e4 = zis4.nextZipEntry; continue
                                        }
                                    } else {
                                        if (skipOneDriveCheck) {
                                            val d = aDone.incrementAndGet()
                                            val s = aSkipped.incrementAndGet()
                                            if (s % 50 == 0 || s == 1) {
                                                val pct = if (total > 0) d * 100 / total else 0
                                                notifyProgress("스킵 중 ($pct%) ${s}개 완료", d, total)
                                                progressCallback?.invoke(TakeoutProgress(d, total, aErrors.get(), false, null, aDoneBytes.get(), s))
                                            }
                                            drainZipEntry(zis4); e4 = zis4.nextZipEntry; continue
                                        }
                                        val checkPath2 = "$fp/$fn"
                                        val exists2 = checkFileExistsSuspend(api, checkPath2)
                                        if (exists2 == null) {
                                            synchronized(lock) { uploaded.remove(e4.name) }
                                            liveLog("🔄 재업로드 필요: $fn (OneDrive에서 삭제됨)")
                                        } else {
                                            val d = aDone.incrementAndGet()
                                            val s = aSkipped.incrementAndGet()
                                            if (s % 50 == 0 || s == 1) {
                                                val pct = if (total > 0) d * 100 / total else 0
                                                notifyProgress("스킵 중 ($pct%) ${s}개 완료", d, total)
                                                progressCallback?.invoke(TakeoutProgress(d, total, aErrors.get(), false, null, aDoneBytes.get(), s))
                                            }
                                            drainZipEntry(zis4); e4 = zis4.nextZipEntry; continue
                                        }
                                    }
                                }

                                val threshold = 4 * 1024 * 1024
                                val baos = java.io.ByteArrayOutputStream(65536)
                                var tmpFile: java.io.File? = null
                                var tmpOut: java.io.OutputStream? = null
                                val buf = ByteArray(32768)
                                var totalRead = 0L
                                var n = zis4.read(buf)
                                while (n != -1) {
                                    totalRead += n.toLong()
                                    if (tmpOut != null) {
                                        tmpOut.write(buf, 0, n)
                                    } else if (totalRead > threshold) {
                                        tmpFile = java.io.File(cacheDir, "takeout_tmp_${System.currentTimeMillis()}")
                                        tmpOut = tmpFile.outputStream().buffered()
                                        tmpOut.write(baos.toByteArray())
                                        tmpOut.write(buf, 0, n)
                                    } else {
                                        baos.write(buf, 0, n)
                                    }
                                    n = zis4.read(buf)
                                }
                                tmpOut?.flush(); tmpOut?.close()
                                val data: ByteArray
                                if (tmpFile != null) {
                                    baos.reset()
                                    data = ByteArray(0)
                                } else {
                                    data = baos.toByteArray()
                                    baos.reset()
                                }

                                try {
                                    channel.send(UploadItem(e4.name, fn, fp, data, totalRead, tmpFile))
                                } catch (ce: kotlinx.coroutines.CancellationException) {
                                    tmpFile?.delete()
                                    throw ce
                                }
                            } else { drainZipEntry(zis4) }
                            e4 = zis4.nextZipEntry
                        }
                    } finally { zis4.close(); channel.close() }
                }

                val workers = (1..3).map { workerId ->
                    launch {
                        for (item in channel) {
                            try {
                                val pathInfo = item.fp.substringAfter(api.rootFolder + "/")
                                updateWorkerLog(workerId, "[W$workerId] ⏳ ${item.fn} ($pathInfo) 업로드 중...")

                                val folderOk = run {
                                    if (item.fp in createdFolders) return@run true
                                    val fLock = folderLocks.getOrPut(item.fp) { Mutex() }
                                    fLock.withLock {
                                        if (item.fp in createdFolders) return@withLock true
                                        val ok = ensureFolderSuspend(api, item.fp)
                                        if (ok) {
                                            val parts = item.fp.split("/")
                                            for (i in 1..parts.size) {
                                                createdFolders.add(parts.take(i).joinToString("/"))
                                            }
                                        }
                                        ok
                                    }
                                }
                                var driveItemId: String? = null
                                if (folderOk) {
                                    val isLarge = item.fileSize > 50 * 1024 * 1024
                                    try {
                                        if (isLarge) {
                                            updateWorkerLog(workerId, "[W$workerId] ⏳ ${item.fn} 대용량(${item.fileSize / 1048576}MB) 업로드 중...")
                                            largeFileMutex.lock()
                                        }
                                        val uploadData = if (item.tmpFile != null && item.tmpFile.exists()) {
                                            val bytes = item.tmpFile.readBytes()
                                            item.tmpFile.delete()
                                            bytes
                                        } else item.data
                                        for (attempt in 1..3) {
                                            driveItemId = uploadFileSuspend(api, uploadData, item.fn, item.fp)
                                            if (driveItemId != null) break
                                            if (attempt < 3) {
                                                updateWorkerLog(workerId, "[W$workerId] ⚠ ${item.fn} 재시도 $attempt/3...")
                                                kotlinx.coroutines.delay(1000L * attempt)
                                            }
                                        }
                                    } catch (oom: OutOfMemoryError) {
                                        updateWorkerLog(workerId, "[W$workerId] ❌ ${item.fn} 메모리 부족 (${item.fileSize / 1048576}MB)")
                                        System.gc()
                                    } finally {
                                        if (isLarge) largeFileMutex.unlock()
                                        item.tmpFile?.delete()
                                    }
                                }

                                if (driveItemId != null) {
                                    val d = aDone.incrementAndGet()
                                    aDoneBytes.addAndGet(item.fileSize)
                                    if (actualUploadStartTime == 0L) actualUploadStartTime = System.currentTimeMillis()
                                    synchronized(lock) {
                                        uploaded.add(item.zipName)
                                        addUploadedFile(item.zipName)
                                    }
                                    saveDriveItemId(item.zipName, driveItemId)
                                    val pct = if (total > 0) d * 100 / total else 0
                                    val sizeKB = String.format("%.1f", item.fileSize / 1024.0)
                                    val elapsedSec = if (actualUploadStartTime > 0) (System.currentTimeMillis() - actualUploadStartTime) / 1000.0 else 0.0
                                    val avgSpeed = if (elapsedSec > 0) String.format("%.1f", aDoneBytes.get() / 1048576.0 / elapsedSec) else "?"
                                    updateWorkerLog(workerId, "[W$workerId] ✅ ${item.fn} (${sizeKB}KB) ${avgSpeed}MB/s")
                                    notifyProgress("$pct% ($d/$total) ${avgSpeed}MB/s", d, total)
                                    val prog = TakeoutProgress(d, total, aErrors.get(), false, null, aDoneBytes.get(), aSkipped.get())
                                    currentProgress = prog
                                    progressCallback?.invoke(prog)
                                } else {
                                    aErrors.incrementAndGet()
                                    val reason = if (!folderOk) "폴더 생성 실패" else "업로드 실패 (3회 재시도)"
                                    updateWorkerLog(workerId, "[W$workerId] ❌ ${item.fn} - $reason")
                                    val prog = TakeoutProgress(aDone.get(), total, aErrors.get(), false, null, aDoneBytes.get(), aSkipped.get())
                                    currentProgress = prog
                                    progressCallback?.invoke(prog)
                                }
                            } finally { item.tmpFile?.delete() }
                        }
                    }
                }

                producer.join()
                workers.forEach { it.join() }

                flushUploadedFiles()
                flushDriveItemIds()

                val done = aDone.get(); val errors = aErrors.get(); val skipped = aSkipped.get(); val doneBytes = aDoneBytes.get()
                val elapsedSec = if (actualUploadStartTime > 0) (System.currentTimeMillis() - actualUploadStartTime) / 1000.0 else 0.0
                val avgSpeed = if (elapsedSec > 0) String.format("%.1f", doneBytes / 1048576.0 / elapsedSec) else "?"
                val msg = "업로드 완료! ${done - skipped}개 업로드, ${skipped}개 스킵, ${errors}개 실패 (평균 ${avgSpeed}MB/s)"
                liveLog(msg)
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildDoneNotification("✅ $msg"))
                progressCallback?.invoke(TakeoutProgress(done, total, errors, true, null, doneBytes, skipped))
                isRunning = false
                withContext(Dispatchers.Main) { stopForeground(STOP_FOREGROUND_DETACH); stopSelf() }
            } catch (e: CancellationException) {
                isRunning = false
                flushUploadedFiles()
                flushDriveItemIds()
                liveLog("업로드 중단됨 (이어하기 가능)")
                progressCallback?.invoke(TakeoutProgress(0,0,0,true,"중단됨 - 이어하기 가능"))
                stopForeground(STOP_FOREGROUND_DETACH); stopSelf()
            } catch (e: Exception) {
                liveLog("오류: ${e.message}")
                progressCallback?.invoke(TakeoutProgress(0,0,0,true,e.message))
                stopForeground(STOP_FOREGROUND_DETACH); stopSelf()
            }
        }
    }

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

    override fun onDestroy() { android.util.Log.w("TakeoutUpload", "onDestroy called - service killed"); super.onDestroy(); try { logWriter?.close() } catch (_: Exception) {}; job?.cancel(); scope.cancel() }
}
