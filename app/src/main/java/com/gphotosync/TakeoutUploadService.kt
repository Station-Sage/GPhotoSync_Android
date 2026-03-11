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
import java.io.InputStream
import java.io.ByteArrayOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream

class CountingInputStream(private val wrapped: InputStream) : InputStream() {
    var bytesRead: Long = 0L
        private set
    override fun read(): Int {
        val b = wrapped.read()
        if (b != -1) bytesRead++
        return b
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = wrapped.read(b, off, len)
        if (n > 0) bytesRead += n
        return n
    }
    override fun close() = wrapped.close()
    override fun available() = wrapped.available()
}

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
        val logBuffer = mutableListOf<String>()
        private const val LOG_BUFFER_MAX = 200
        var analyzeCallback: ((Int, Long, Int) -> Unit)? = null
        var organizeCallback: ((Int, Int, Int) -> Unit)? = null  // (total, copied, errors)
        var migrateCallback: ((Int, Int, Int) -> Unit)? = null
        var authExpiredCallback: (() -> Unit)? = null
        var skipOneDriveCheck: Boolean = false
        @Volatile var isRunning: Boolean = false
        @Volatile var currentProgress: TakeoutProgress? = null
        @Volatile var uploadStartTime: Long = 0L   // (total, moved, errors)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    private val jsonDateMap = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val folderMutex = Mutex()
    private val createdFolders = mutableSetOf<String>()

    // === OkHttp 콜백 → suspend 변환 ===
    private suspend fun ensureFolderSuspend(api: OneDriveApi, path: String): Boolean {
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

    private suspend fun uploadFileSuspend(api: OneDriveApi, data: ByteArray, fn: String, fp: String): String? =
        suspendCoroutine { cont -> api.uploadFile(data, fn, fp) { cont.resume(it) } }


    private suspend fun getItemIdSuspend(api: OneDriveApi, path: String): String? =
        suspendCoroutine { cont -> api.getItemId(path) { cont.resume(it) } }

    private suspend fun createAlbumSuspend(api: OneDriveApi, name: String, ids: List<String>): Boolean =
        suspendCoroutine { cont -> api.createAlbum(name, ids) { cont.resume(it) } }

    private suspend fun moveFileSuspend(api: OneDriveApi, itemId: String, destFolderId: String): Boolean =
        suspendCoroutine { cont -> api.moveFile(itemId, destFolderId) { cont.resume(it) } }

    private suspend fun listChildrenSuspend(api: OneDriveApi, folderId: String): List<Triple<String, String, Boolean>>? =
        suspendCoroutine { cont -> api.listChildren(folderId) { cont.resume(it) } }

    private suspend fun deleteItemSuspend(api: OneDriveApi, itemId: String): Boolean =
        suspendCoroutine { cont -> api.deleteItem(itemId) { cont.resume(it) } }

    private suspend fun getFolderIdSuspend(api: OneDriveApi, path: String): String? =
        suspendCoroutine { cont -> api.getFolderId(path) { cont.resume(it) } }

    private suspend fun checkFileExistsSuspend(api: OneDriveApi, path: String): Long? =
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

    // === 스트림 드레인: 메모리에 올리지 않고 스트림 소비 ===
    private val drainBuf = ByteArray(262144) // 256KB
    private fun drain(zis: ZipArchiveInputStream) {
        try { while (zis.read(drainBuf) != -1) { } } catch (_: Exception) {}
    }

    // === JSON을 스트리밍으로 읽기 (최대 64KB만, 그 이상은 스킵) ===
    private fun readJsonSafe(zis: ZipArchiveInputStream): String? {
        val maxSize = 65536
        val baos = ByteArrayOutputStream(8192)
        val buf = ByteArray(32768)
        var total = 0
        var n = zis.read(buf)
        while (n != -1) {
            total += n
            if (total <= maxSize) baos.write(buf, 0, n)
            n = zis.read(buf)
        }
        return if (total <= maxSize) baos.toString(Charsets.UTF_8.name()) else null
    }

    // === 진행 상태 저장/로드 ===
    private fun saveAnalyzeState(sc: Int, mc: Int, ts: Long, jc: Int) {
        getSharedPreferences("takeout_analyze", MODE_PRIVATE).edit()
            .putInt("sc", sc).putInt("mc", mc).putLong("ts", ts).putInt("jc", jc).apply()
    }
    private fun saveMediaList(names: Set<String>) {
        getSharedPreferences("takeout_media_list", MODE_PRIVATE).edit()
            .putStringSet("names", names).apply()
    }
    private fun loadMediaList(): Set<String> {
        return getSharedPreferences("takeout_media_list", MODE_PRIVATE)
            .getStringSet("names", emptySet()) ?: emptySet()
    }
    private fun clearMediaList() {
        getSharedPreferences("takeout_media_list", MODE_PRIVATE).edit().clear().apply()
    }
    private fun loadAnalyzeState(): LongArray {
        val p = getSharedPreferences("takeout_analyze", MODE_PRIVATE)
        return longArrayOf(p.getInt("sc",0).toLong(), p.getInt("mc",0).toLong(), p.getLong("ts",0L), p.getInt("jc",0).toLong())
    }
    private fun clearAnalyzeState() { getSharedPreferences("takeout_analyze", MODE_PRIVATE).edit().clear().apply() }

    private fun saveJsonDateMap() {
        val p = getSharedPreferences("takeout_json_map", MODE_PRIVATE).edit().clear()
        p.putInt("c", jsonDateMap.size)
        var i = 0
        for ((k, v) in jsonDateMap) { p.putString("k$i", k).putString("v$i", v); i++ }
        p.apply()
    }
    private fun loadJsonDateMap() {
        val p = getSharedPreferences("takeout_json_map", MODE_PRIVATE)
        jsonDateMap.clear()
        for (i in 0 until p.getInt("c", 0)) {
            val k = p.getString("k$i", null) ?: continue
            val v = p.getString("v$i", null) ?: continue
            jsonDateMap[k] = v.substringBefore("/") // 연도만 유지 (이전 버전 호환)
        }
    }

    private fun getUploadedFiles(): MutableSet<String> =
        getSharedPreferences("takeout_progress", MODE_PRIVATE).getStringSet("uf", emptySet())?.toMutableSet() ?: mutableSetOf()
    private var pendingUploaded = mutableSetOf<String>()
    private var lastFlushTime = 0L

    @Synchronized
    private fun addUploadedFile(name: String) {
        pendingUploaded.add(name)
        if (pendingUploaded.size >= 5) flushUploadedFiles()
    }

    @Synchronized
    private fun flushUploadedFiles() {
        if (pendingUploaded.isEmpty()) return
        val p = getSharedPreferences("takeout_progress", MODE_PRIVATE)
        val s = p.getStringSet("uf", emptySet())?.toMutableSet() ?: mutableSetOf()
        s.addAll(pendingUploaded)
        p.edit().putStringSet("uf", s).apply()
        pendingUploaded.clear()
    }
    private fun clearUploadedFiles() { getSharedPreferences("takeout_progress", MODE_PRIVATE).edit().clear().apply() }

    // driveItem id 저장 (파일명 → id)
    private val pendingDriveIds = mutableMapOf<String, String>()

    @Synchronized
    private fun saveDriveItemId(filename: String, driveId: String) {
        pendingDriveIds[filename] = driveId
        if (pendingDriveIds.size >= 10) flushDriveItemIds()
    }

    @Synchronized
    private fun flushDriveItemIds() {
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


    // 앨범 매핑 저장/로드 (파일 ZIP경로 → 앨범명)
    private fun saveAlbumMap(map: Map<String, String>) {
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
    private fun liveLog(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        try { getLogWriter()?.apply { write("$ts $msg"); newLine(); flush() } } catch (_: Exception) {}
        val entry = "[$ts] $msg"
        synchronized(logBuffer) { logBuffer.add(entry); if (logBuffer.size > LOG_BUFFER_MAX) logBuffer.removeAt(0) }
        android.os.Handler(android.os.Looper.getMainLooper()).post { logCallback?.invoke("[$ts] $msg") }
    }

    private val imageExt = setOf("jpg","jpeg","png","gif","bmp","webp","heic","heif","tiff","tif","raw","cr2","nef","arw","dng","svg")
    private val videoExt = setOf("mp4","mov","avi","mkv","wmv","flv","webm","m4v","3gp","3g2","mts","m2ts")
    private fun isMedia(name: String) = name.substringAfterLast('.', "").lowercase().let { it in imageExt || it in videoExt }

    private fun parseJsonMeta(json: String): Pair<String, String>? {
        try {
            val obj = org.json.JSONObject(json)
            val title = obj.optString("title", "")
            var ts = 0L
            if (obj.has("photoTakenTime")) ts = obj.getJSONObject("photoTakenTime").optString("timestamp","0").toLongOrNull() ?: 0L
            if (ts == 0L && obj.has("creationTime")) ts = obj.getJSONObject("creationTime").optString("timestamp","0").toLongOrNull() ?: 0L
            if (title.isNotEmpty() && ts > 0) {
                val d = java.util.Date(ts * 1000)
                val y = java.text.SimpleDateFormat("yyyy", java.util.Locale.getDefault()).format(d)
                return Pair(title, y)
            }
        } catch (_: Exception) {}
        return null
    }


    // Takeout ZIP 경로에서 앨범명 추출
    // "Takeout/Google Photos/Photos from 2024/file.jpg" → null (날짜 폴더)
    // "Takeout/Google Photos/여행 2023/file.jpg" → "여행 2023" (앨범)
    private val dateFolderPatterns = listOf(
        Regex("""Photos from \d{4}"""),
        Regex("""^\d{4}년\s*\d{1,2}월\s*\d{1,2}일$"""),
        Regex("""^\d{4}-\d{2}-\d{2}$""")
    )

    private fun extractAlbumName(zipPath: String): String? {
        // 경로: Takeout/Google Photos/폴더명/파일명 또는 Takeout/Google 포토/폴더명/파일명
        val parts = zipPath.split("/")
        if (parts.size < 3) return null

        // "Google Photos" 또는 "Google 포토" 다음 폴더가 앨범 후보
        val gpIdx = parts.indexOfFirst { it == "Google Photos" || it == "Google 포토" || it.startsWith("Google") }
        if (gpIdx < 0 || gpIdx + 1 >= parts.size - 1) return null

        val folderName = parts[gpIdx + 1]

        // 날짜 폴더 패턴이면 앨범 아님
        for (p in dateFolderPatterns) {
            if (p.matches(folderName)) return null
        }

        // 빈 문자열이나 너무 짧으면 무시
        if (folderName.isBlank() || folderName.length < 2) return null

        return folderName
    }

    private fun yearOnly(filename: String, path: String): String {
        jsonDateMap[filename]?.let {
            // 이전 버전 호환: "2015/05" 형태면 연도만 추출
            return it.substringBefore("/")
        }
        val yr = Regex("""((?:19|20)\d{2})""")
        (yr.find(filename) ?: yr.find(path))?.let { return it.groupValues[1] }
        return "unknown"
    }

    private fun dateFromName(filename: String, path: String): String? {
        val p = Regex("""((?:19|20)\d{2})[-_]?(\d{2})[-_]?(\d{2})""")
        val m = p.find(filename) ?: p.find(path) ?: return null
        return "${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]}"
    }

    private fun inRange(fd: String?, s: String?, e: String?): Boolean {
        if (s == null && e == null) return true
        if (fd == null) return false
        if (s != null && fd < s) return false
        if (e != null && fd > e) return false
        return true
    }

    // ======== ZIP 분석 ========
    private fun analyzeZip(zipUri: Uri, startDate: String?, endDate: String?, resume: Boolean) {
        job = scope.launch {
            try {
                var skipCount = 0; var mediaCount = 0; var totalSize = 0L; var jsonCount = 0

                if (resume) {
                    val st = loadAnalyzeState()
                    skipCount = st[0].toInt(); mediaCount = st[1].toInt(); totalSize = st[2]; jsonCount = st[3].toInt()
                    loadJsonDateMap()
                    liveLog("분석 이어하기: ${skipCount}개부터 재개 (미디어 ${mediaCount}, JSON ${jsonCount})")
                } else {
                    clearAnalyzeState(); jsonDateMap.clear()
                    liveLog("ZIP 파일 분석 시작...")
                }

                var zipBytes = 0L
                try { contentResolver.openFileDescriptor(zipUri, "r")?.use { zipBytes = it.statSize } } catch (_: Exception) {}
                val zipMB = if (zipBytes > 0) String.format("%.0f", zipBytes / 1048576.0) else "?"

                val cs = CountingInputStream(java.io.BufferedInputStream(contentResolver.openInputStream(zipUri)!!, 524288)) // 512KB 버퍼
                val zis = ZipArchiveInputStream(cs)
                val mediaNames = mutableSetOf<String>()
                val albumMap = mutableMapOf<String, String>()
                var sc = 0
                var entry = zis.nextZipEntry

                while (entry != null && isActive) {
                    if (!entry.isDirectory) {
                        sc++
                        if (sc <= skipCount) {
                            drain(zis)
                            if (sc.rem(2000) == 0) notifyProgress("이전 위치로 이동 중... $sc/$skipCount", sc, skipCount)
                            entry = zis.nextZipEntry; continue
                        }

                        if (entry.name.endsWith(".json")) {
                            val js = readJsonSafe(zis)
                            if (js != null) {
                                val r = parseJsonMeta(js)
                                if (r != null) { jsonDateMap[r.first] = r.second; jsonCount++ }
                            }
                        } else if (isMedia(entry.name)) {
                            val fn = entry.name.substringAfterLast('/')
                            val fd = dateFromName(fn, entry.name)
                            if (inRange(fd, startDate, endDate)) {
                                mediaCount++; val sz = entry.size; if (sz > 0) totalSize += sz; mediaNames.add(entry.name)
                                val album = extractAlbumName(entry.name)
                                if (album != null) albumMap[entry.name] = album
                            }
                            drain(zis)
                        } else {
                            drain(zis)
                        }

                        if (sc.rem(500) == 0) {
                            saveAnalyzeState(sc, mediaCount, totalSize, jsonCount)
                            if (sc.rem(2000) == 0) saveJsonDateMap()
                            val pct = if (zipBytes > 0) (cs.bytesRead * 100 / zipBytes).toInt() else 0
                            val rdMB = String.format("%.0f", cs.bytesRead / 1048576.0)
                            notifyProgress("분석 $pct% ($rdMB/$zipMB MB) | 파일$sc 미디어$mediaCount",
                                if (zipBytes > 0) (cs.bytesRead / 1048576).toInt() else 0,
                                if (zipBytes > 0) (zipBytes / 1048576).toInt() else 0)
                            if (sc.rem(2000) == 0) liveLog("분석 $pct%: ${sc}개 스캔, 미디어 ${mediaCount}, JSON $jsonCount")
                            if (zipBytes > 0) progressCallback?.invoke(TakeoutProgress(pct, 100, 0, false, null, cs.bytesRead, 0))
                        }
                    }
                    entry = zis.nextZipEntry
                }
                zis.close()

                // 루프 종료 시 항상 최신 상태 저장
                saveAnalyzeState(sc, mediaCount, totalSize, jsonCount)
                saveJsonDateMap()

                if (!isActive) {
                    liveLog("분석 중단 (${sc}개 스캔 완료, 이어하기 가능)")
                    android.os.Handler(android.os.Looper.getMainLooper()).post { analyzeCallback?.invoke(-2, totalSize, sc) }
                    stopForeground(STOP_FOREGROUND_DETACH); stopSelf(); return@launch
                }

                saveJsonDateMap(); saveMediaList(mediaNames); saveAlbumMap(albumMap); clearAnalyzeState()
                liveLog("분석 완료: 전체 ${sc}개, 미디어 ${mediaCount}개, JSON ${jsonCount}개")
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildDoneNotification("✅ 분석 완료: 미디어 ${mediaCount}개"))
                android.os.Handler(android.os.Looper.getMainLooper()).post { analyzeCallback?.invoke(mediaCount, totalSize, sc) }
                stopForeground(STOP_FOREGROUND_DETACH); stopSelf()
            } catch (e: CancellationException) {
                // 중단 시 마지막 상태 저장 (sc는 접근 불가하므로 이미 100개 단위로 저장된 상태 유지)
                saveJsonDateMap()
                liveLog("분석 중단됨 (이어하기 가능)")
                android.os.Handler(android.os.Looper.getMainLooper()).post { analyzeCallback?.invoke(-2, 0L, 0) }
                stopForeground(STOP_FOREGROUND_DETACH); stopSelf()
            } catch (e: Exception) {
                liveLog("ZIP 분석 실패: ${e.message}")
                android.os.Handler(android.os.Looper.getMainLooper()).post { analyzeCallback?.invoke(-1, 0L, 0) }
                stopForeground(STOP_FOREGROUND_DETACH); stopSelf()
            }
        }
    }



    // ======== 마이그레이션: 월 폴더 → 연도 폴더 ========
    private fun migrateMonthToYear() {
        val api = OneDriveApi(this)
        job = scope.launch {
            try {
                liveLog("월 폴더 → 연도 폴더 정리 시작...")
                notifyProgress("폴더 정리 중...", 0, 0)

                // rootFolder 하위의 연도 폴더 목록 가져오기
                val rootId = getFolderIdSuspend(api, api.rootFolder)
                if (rootId == null) {
                    liveLog("❌ 루트 폴더를 찾을 수 없음: ${api.rootFolder}")
                    android.os.Handler(android.os.Looper.getMainLooper()).post { migrateCallback?.invoke(0, 0, 1) }
                    stopForeground(STOP_FOREGROUND_DETACH); stopSelf(); return@launch
                }

                val yearFolders = listChildrenSuspend(api, rootId)?.filter { it.third } ?: emptyList()
                liveLog("연도 폴더 ${yearFolders.size}개 발견")

                var totalMoved = 0; var totalErrors = 0; var totalFiles = 0

                for ((yearFolderId, yearName, _) in yearFolders) {
                    if (!isActive) break
                    // 연도 폴더 내 월 하위폴더 탐색
                    val children = listChildrenSuspend(api, yearFolderId) ?: continue
                    val monthFolders = children.filter { it.third && it.second.matches(Regex("""^\d{1,2}$|^unknown$""")) }

                    if (monthFolders.isEmpty()) continue
                    liveLog("📂 $yearName: 월 폴더 ${monthFolders.size}개 발견")

                    for ((monthFolderId, monthName, _) in monthFolders) {
                        if (!isActive) break
                        val files = listChildrenSuspend(api, monthFolderId) ?: continue
                        val fileItems = files.filter { !it.third } // 파일만

                        liveLog("  📁 $yearName/$monthName: 파일 ${fileItems.size}개 이동 중...")
                        totalFiles += fileItems.size

                        for ((fileId, fileName, _) in fileItems) {
                            if (!isActive) break
                            val ok = moveFileSuspend(api, fileId, yearFolderId)
                            if (ok) {
                                totalMoved++
                            } else {
                                totalErrors++
                                liveLog("  ⚠ 이동 실패: $fileName")
                            }
                        }

                        // 폴더가 비었으면 삭제
                        val remaining = listChildrenSuspend(api, monthFolderId)
                        if (remaining != null && remaining.isEmpty()) {
                            deleteItemSuspend(api, monthFolderId)
                            liveLog("  🗑 빈 폴더 삭제: $yearName/$monthName")
                        }

                        notifyProgress("$yearName/$monthName 정리 중...", totalMoved, totalFiles)
                    }
                }

                // uploaded 목록의 경로 정보도 갱신 (월 경로 제거)
                val msg = "폴더 정리 완료! 이동: $totalMoved, 실패: $totalErrors (전체 $totalFiles)"
                liveLog(msg)
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildDoneNotification("✅ $msg"))
                android.os.Handler(android.os.Looper.getMainLooper()).post { migrateCallback?.invoke(totalFiles, totalMoved, totalErrors) }
                stopForeground(STOP_FOREGROUND_DETACH); stopSelf()
            } catch (e: CancellationException) {
                liveLog("폴더 정리 중단됨")
                stopForeground(STOP_FOREGROUND_DETACH); stopSelf()
            } catch (e: Exception) {
                liveLog("폴더 정리 오류: ${e.message}")
                stopForeground(STOP_FOREGROUND_DETACH); stopSelf()
            }
        }
    }

    // ======== 앨범 정리 (OneDrive bundle 앨범 생성 — 용량 0) ========
    private fun organizeAlbums() {
        val api = OneDriveApi(this)
        job = scope.launch {
            try {
                val albumMap = loadAlbumMap()
                if (albumMap.isEmpty()) {
                    liveLog("앨범 매핑 데이터 없음. 먼저 ZIP 분석을 실행하세요.")
                    android.os.Handler(android.os.Looper.getMainLooper()).post { organizeCallback?.invoke(0, 0, 0) }
                    stopForeground(STOP_FOREGROUND_DETACH); stopSelf(); return@launch
                }

                val uploaded = Collections.synchronizedSet(getUploadedFiles())
                val albumFiles = albumMap.filter { it.key in uploaded }
                if (albumFiles.isEmpty()) {
                    liveLog("앨범 정리 대상 없음 (업로드된 앨범 파일 없음)")
                    android.os.Handler(android.os.Looper.getMainLooper()).post { organizeCallback?.invoke(0, 0, 0) }
                    stopForeground(STOP_FOREGROUND_DETACH); stopSelf(); return@launch
                }

                if (jsonDateMap.isEmpty()) loadJsonDateMap()

                // 저장된 driveItem id 로드
                val savedIds = loadDriveItemIds()

                // 앨범별로 그룹핑
                val albumGroups = mutableMapOf<String, MutableList<String>>() // 앨범명 → zipPath 목록
                for ((zipPath, albumName) in albumFiles) {
                    albumGroups.getOrPut(albumName) { mutableListOf() }.add(zipPath)
                }

                val total = albumGroups.size
                var done = 0; var errors = 0
                liveLog("앨범 생성 시작: ${total}개 앨범 (${albumFiles.size}개 파일)")
                notifyProgress("앨범 생성 (${total}개)", 0, total)
                progressCallback?.invoke(TakeoutProgress(0, total, 0, false, null))

                for ((albumName, zipPaths) in albumGroups) {
                    if (!isActive) break

                    // 각 파일의 driveItem id 수집
                    val ids = mutableListOf<String>()
                    for (zp in zipPaths) {
                        // 1. 저장된 id가 있으면 사용
                        val savedId = savedIds[zp]
                        if (savedId != null) { ids.add(savedId); continue }

                        // 2. 없으면 OneDrive에서 경로로 조회
                        val fn = zp.substringAfterLast('/')
                        val yr = yearOnly(fn, zp)
                        val filePath = "${api.rootFolder}/$yr/$fn"
                        val id = getItemIdSuspend(api, filePath)
                        if (id != null) {
                            ids.add(id)
                            saveDriveItemId(zp, id) // 다음에 재사용
                        } else {
                            liveLog("⚠ id 조회 실패: $fn")
                        }
                    }

                    done++
                    if (ids.isEmpty()) {
                        errors++
                        liveLog("❌ $albumName: 파일 id 없음")
                        progressCallback?.invoke(TakeoutProgress(done, total, errors, false, null))
                        continue
                    }

                    // bundle 앨범 생성
                    val ok = createAlbumSuspend(api, albumName, ids)
                    if (ok) {
                        val pct = done * 100 / total
                        liveLog("📁 $albumName (${ids.size}개 파일)")
                        notifyProgress("앨범 생성 $pct% - $albumName", done, total)
                    } else {
                        errors++
                        liveLog("❌ 앨범 생성 실패: $albumName")
                    }
                    progressCallback?.invoke(TakeoutProgress(done, total, errors, false, null))
                }

                val msg = "앨범 생성 완료! ${done - errors}개 앨범 (실패:$errors)"
                liveLog(msg)
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildDoneNotification("✅ $msg"))
                progressCallback?.invoke(TakeoutProgress(done, total, errors, true, null))
                android.os.Handler(android.os.Looper.getMainLooper()).post { organizeCallback?.invoke(total, done - errors, errors) }
                stopForeground(STOP_FOREGROUND_DETACH); stopSelf()
            } catch (e: CancellationException) {
                liveLog("앨범 정리 중단됨")
                progressCallback?.invoke(TakeoutProgress(0, 0, 0, true, "중단됨"))
                stopForeground(STOP_FOREGROUND_DETACH); stopSelf()
            } catch (e: Exception) {
                liveLog("앨범 정리 오류: ${e.message}")
                progressCallback?.invoke(TakeoutProgress(0, 0, 0, true, e.message))
                stopForeground(STOP_FOREGROUND_DETACH); stopSelf()
            }
        }
    }


    // ======== 업로드 ========
    private fun startUpload(zipUri: Uri, startDate: String? = null, endDate: String? = null, resume: Boolean = false) {
        val api = OneDriveApi(this)
        job = scope.launch {
            try {
                // JSON 메타데이터 로드 (분석에서 이미 수집된 경우)
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
                        } else { drain(zis2) }
                        je = zis2.nextZipEntry
                    }
                    zis2.close()
                    saveJsonDateMap()
                    liveLog("JSON ${jc}개 수집 완료")
                }

                // 분석에서 저장된 미디어 목록 로드
                liveLog("미디어 파일 목록 로드 중...")
                var mediaNames = loadMediaList()
                if (mediaNames.isEmpty()) {
                    liveLog("저장된 목록 없음. ZIP에서 목록 수집 중...")
                    val tempNames = mutableSetOf<String>()
                    val zis3 = ZipArchiveInputStream(contentResolver.openInputStream(zipUri))
                    var e3 = zis3.nextZipEntry
                    while (e3 != null && isActive) {
                        if (!e3.isDirectory && isMedia(e3.name)) {
                            val fn = e3.name.substringAfterLast('/')
                            if (inRange(dateFromName(fn, e3.name), startDate, endDate)) tempNames.add(e3.name)
                        }
                        drain(zis3)
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
                // MS 토큰 유효성 검사 + 자동 갱신
                liveLog("🔑 MS 인증 확인 중...")
                val tokenOk = suspendCoroutine<Boolean> { cont ->
                    com.gphotosync.TokenManager.getValidMicrosoftToken(okhttp3.OkHttpClient()) { token ->
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
                // 루트 폴더 미리 생성 (Pictures/GooglePhotos)
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

                // 기존 연도 폴더 목록 미리 캐싱
                try {
                    val children = suspendCoroutine<List<Triple<String, String, Boolean>>> { cont ->
                        api.listChildren(api.rootFolder) { cont.resume(it ?: emptyList()) }
                    }
                    val yearFolders = children.filter { it.third }.map { it.second }
                    synchronized(createdFolders) {
                        yearFolders.forEach { name ->
                            createdFolders.add("${api.rootFolder}/$name")
                        }
                    }
                    liveLog("📂 기존 폴더 ${yearFolders.size}개 캐싱: ${yearFolders.take(5).joinToString()}...")
                } catch (e: Exception) {
                    liveLog("⚠ 폴더 캐싱 실패 (계속 진행): ${e.message}")
                }

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
                    // 새 업로드도 기존 완료 목록은 유지 (스킵 가속)
                    uploaded = Collections.synchronizedSet(getUploadedFiles())
                    if (uploaded.isNotEmpty()) liveLog("기존 완료 ${uploaded.size}개는 스킵합니다")
                }

                val aDone = AtomicInteger(0); val aErrors = AtomicInteger(0)
                val aSkipped = AtomicInteger(0); val aDoneBytes = AtomicLong(0L)
                val lock = Any() // 공유 자원 동기화용

                // 파이프라인: ZIP 읽기 → Channel → 병렬 업로드 (3 workers)
                data class UploadItem(val zipName: String, val fn: String, val fp: String, val data: ByteArray, val fileSize: Long, val tmpFile: java.io.File?)
                val channel = Channel<UploadItem>(8) // 버퍼 8개: Producer가 미리 읽어둠

                // Producer: ZIP에서 읽어서 Channel에 전달
                val producer = launch {
                    val zis4 = ZipArchiveInputStream(java.io.BufferedInputStream(contentResolver.openInputStream(zipUri), 524288))
                    var e4 = zis4.nextZipEntry
                    try {
                        while (e4 != null && isActive) {
                            if (!e4.isDirectory && e4.name in mediaNames) {
                                val fn = e4.name.substringAfterLast('/')
                                // 모든 파일은 연도 폴더에 업로드 (앨범은 bundle로 별도 생성)
                                val ym = yearOnly(fn, e4.name)
                                val fp = "${api.rootFolder}/$ym"

                                if (e4.name in uploaded) {
                                    if (resume) {
                                        // 이어하기 시: OneDrive에 실제 존재하는지 확인
                                        if (skipOneDriveCheck) {
                                            val d = aDone.incrementAndGet()
                                            val s = aSkipped.incrementAndGet()
                                            if (s % 50 == 0 || s == 1) {
                                                val pct = if (total > 0) d * 100 / total else 0
                                                notifyProgress("스킵 중 ($pct%) ${s}개 완료", d, total)
                                                progressCallback?.invoke(TakeoutProgress(d, total, aErrors.get(), false, null, aDoneBytes.get(), s))
                                            }
                                            drain(zis4); e4 = zis4.nextZipEntry; continue
                                        }
                                        val checkPath = "$fp/$fn"
                                        val exists = checkFileExistsSuspend(api, checkPath)
                                        if (exists == null) {
                                            // 파일이 OneDrive에 없음 → uploaded에서 제거, 다시 업로드
                                            synchronized(lock) { uploaded.remove(e4.name) }
                                            liveLog("🔄 재업로드 필요: $fn (OneDrive에서 삭제됨)")
                                            // continue하지 않고 아래 업로드 로직 진행
                                        } else {
                                            val d = aDone.incrementAndGet()
                                            val s = aSkipped.incrementAndGet()
                                            if (s % 50 == 0 || s == 1) {
                                                val pct = if (total > 0) d * 100 / total else 0
                                                notifyProgress("스킵 중 ($pct%) ${s}개 완료", d, total)
                                                progressCallback?.invoke(TakeoutProgress(d, total, aErrors.get(), false, null, aDoneBytes.get(), s))
                                            }
                                            drain(zis4); e4 = zis4.nextZipEntry; continue
                                        }
                                    } else {
                                        // 새 업로드도 OneDrive 존재 확인
                                        if (skipOneDriveCheck) {
                                            val d = aDone.incrementAndGet()
                                            val s = aSkipped.incrementAndGet()
                                            if (s % 50 == 0 || s == 1) {
                                                val pct = if (total > 0) d * 100 / total else 0
                                                notifyProgress("스킵 중 ($pct%) ${s}개 완료", d, total)
                                                progressCallback?.invoke(TakeoutProgress(d, total, aErrors.get(), false, null, aDoneBytes.get(), s))
                                            }
                                            drain(zis4); e4 = zis4.nextZipEntry; continue
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
                                            drain(zis4); e4 = zis4.nextZipEntry; continue
                                        }
                                    }
                                }

                                // 스트리밍 읽기
                                val threshold = 4 * 1024 * 1024
                                val baos = java.io.ByteArrayOutputStream(65536)
                                var tmpFile: java.io.File? = null
                                var tmpOut: java.io.OutputStream? = null
                                val buf = ByteArray(32768)
                                var totalRead = 0L
                                var n = zis4.read(buf)
                                while (n != -1) {
                                    totalRead += n
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
                                    data = ByteArray(0) // Worker에서 tmpFile로 직접 읽음
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
                            } else { drain(zis4) }
                            e4 = zis4.nextZipEntry
                        }
                    } finally { zis4.close(); channel.close() }
                }

                // Workers: Channel에서 받아 병렬 업로드
                val workers = (1..3).map { workerId ->
                    launch {
                        for (item in channel) {
                            try {
                                val pathInfo = item.fp.substringAfter(api.rootFolder + "/")
                                liveLog("[W$workerId] ${item.fn} ($pathInfo)")

                                val folderOk = folderMutex.withLock {
                                    if (item.fp in createdFolders) true
                                    else {
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
                                    val uploadData = if (item.tmpFile != null && item.tmpFile.exists()) item.tmpFile.readBytes() else item.data
                                    for (attempt in 1..3) {
                                        driveItemId = uploadFileSuspend(api, uploadData, item.fn, item.fp)
                                        if (driveItemId != null) break
                                        if (attempt < 3) {
                                            liveLog("⚠ [W$workerId] ${item.fn} 업로드 실패, 재시도 $attempt/3")
                                            kotlinx.coroutines.delay(1000L * attempt)
                                        }
                                    }
                                }

                                if (driveItemId != null) {
                                    val d = aDone.incrementAndGet()
                                    aDoneBytes.addAndGet(item.fileSize)
                                    synchronized(lock) {
                                        uploaded.add(item.zipName)
                                        addUploadedFile(item.zipName)
                                    }
                                    saveDriveItemId(item.zipName, driveItemId)
                                    val pct = if (total > 0) d * 100 / total else 0
                                    val sizeKB = String.format("%.1f", item.fileSize / 1024.0)
                                    val elapsedSec = (System.currentTimeMillis() - uploadStartTime) / 1000.0
                                    val avgSpeed = if (elapsedSec > 0) String.format("%.1f", aDoneBytes.get() / 1048576.0 / elapsedSec) else "?"
                                    liveLog("✅ ${item.fn} (${sizeKB}KB)")
                                    notifyProgress("업로드 $pct% (${avgSpeed}MB/s) - ${item.fn}", d, total)
                                } else {
                                    aDone.incrementAndGet(); aErrors.incrementAndGet()
                                    val reason = if (!folderOk) "폴더 생성 실패" else "업로드 실패 (3회 재시도)"
                                    liveLog("❌ ${item.fn} - $reason")
                                }
                                val prog = TakeoutProgress(aDone.get(), total, aErrors.get(), false, null, aDoneBytes.get(), aSkipped.get())
                                currentProgress = prog
                                progressCallback?.invoke(prog)
                            } finally { item.tmpFile?.delete() }
                        }
                    }
                }

                // 완료 대기
                producer.join()
                workers.forEach { it.join() }

                flushUploadedFiles()
                flushDriveItemIds()
                // uploaded 목록은 유지 (재실행 시 스킵용)
                // 사용자가 새 ZIP 선택하면 TakeoutTabHelper에서 초기화
                clearMediaList()
                val done = aDone.get(); val errors = aErrors.get(); val skipped = aSkipped.get(); val doneBytes = aDoneBytes.get()
                val ok = done - errors - skipped
                val elapsed = (System.currentTimeMillis() - uploadStartTime) / 1000
                val minutes = elapsed / 60; val seconds = elapsed % 60
                val speedMBs = if (elapsed > 0) String.format("%.1f", doneBytes / 1048576.0 / elapsed) else "N/A"
                val doneMBFinal = String.format("%.1f", doneBytes / 1048576.0)
                val msg = "완료! 성공:$ok 스킵:$skipped 실패:$errors (전체:$total) | ${doneMBFinal}MB ${minutes}분${seconds}초 ${speedMBs}MB/s"
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
    private fun notifyProgress(msg: String, done: Int, total: Int) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyTime < 500 && done < total && done > 0) return  // 500ms 쓰로틀 (첫 호출은 항상 통과)
        lastNotifyTime = now
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification(msg, done, total))
    }

    private fun buildDoneNotification(msg: String): Notification {
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

data class TakeoutProgress(
    val done: Int, val total: Int, val errors: Int, val finished: Boolean,
    val errorMessage: String?, val doneBytes: Long = 0L, val skipped: Int = 0
)
