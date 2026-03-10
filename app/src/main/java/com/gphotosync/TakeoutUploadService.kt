package com.gphotosync

import android.app.*
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
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
        const val EXTRA_ZIP_URI = "zip_uri"
        const val CHANNEL_ID = "takeout_channel"
        const val NOTIF_ID = 2001

        var progressCallback: ((TakeoutProgress) -> Unit)? = null
        var logCallback: ((String) -> Unit)? = null
        var analyzeCallback: ((Int, Long, Int) -> Unit)? = null
        var organizeCallback: ((Int, Int, Int) -> Unit)? = null  // (total, copied, errors)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    private val jsonDateMap = mutableMapOf<String, String>()
    private val createdFolders = mutableSetOf<String>()

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
        }
        return START_NOT_STICKY
    }

    // === 스트림 드레인: 메모리에 올리지 않고 스트림 소비 ===
    private val drainBuf = ByteArray(16384)
    private fun drain(zis: ZipArchiveInputStream) {
        try { while (zis.read(drainBuf) != -1) { } } catch (_: Exception) {}
    }

    // === JSON을 스트리밍으로 읽기 (최대 64KB만, 그 이상은 스킵) ===
    private fun readJsonSafe(zis: ZipArchiveInputStream): String? {
        val maxSize = 65536
        val baos = ByteArrayOutputStream(4096)
        val buf = ByteArray(4096)
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
            jsonDateMap[k] = v
        }
    }

    private fun getUploadedFiles(): MutableSet<String> =
        getSharedPreferences("takeout_progress", MODE_PRIVATE).getStringSet("uf", emptySet())?.toMutableSet() ?: mutableSetOf()
    private var pendingUploaded = mutableSetOf<String>()
    private var lastFlushTime = 0L

    private fun addUploadedFile(name: String) {
        pendingUploaded.add(name)
        val now = System.currentTimeMillis()
        if (now - lastFlushTime > 5000 || pendingUploaded.size % 10 == 0) {
            flushUploadedFiles()
            lastFlushTime = now
        }
    }

    private fun flushUploadedFiles() {
        if (pendingUploaded.isEmpty()) return
        val p = getSharedPreferences("takeout_progress", MODE_PRIVATE)
        val s = p.getStringSet("uf", emptySet())?.toMutableSet() ?: mutableSetOf()
        s.addAll(pendingUploaded)
        p.edit().putStringSet("uf", s).apply()
    }
    private fun clearUploadedFiles() { getSharedPreferences("takeout_progress", MODE_PRIVATE).edit().clear().apply() }


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

    private fun liveLog(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        try {
            val f = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "sync_log.txt")
            f.appendText("$ts $msg" + "\n")
        } catch (_: Exception) {}
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
                val m = java.text.SimpleDateFormat("MM", java.util.Locale.getDefault()).format(d)
                return Pair(title, "$y/$m")
            }
        } catch (_: Exception) {}
        return null
    }


    // Takeout ZIP 경로에서 앨범명 추출
    // "Takeout/Google Photos/Photos from 2024/file.jpg" → null (날짜 폴더)
    // "Takeout/Google Photos/여행 2023/file.jpg" → "여행 2023" (앨범)
    private val datefolderPattern = Regex("""Photos from \d{4}""")
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

    private fun yearMonth(filename: String, path: String): String {
        jsonDateMap[filename]?.let { return it }
        val ym = Regex("""((?:19|20)\d{2})[-_]?(\d{2})[-_]?\d{2}""")
        (ym.find(filename) ?: ym.find(path))?.let { return "${it.groupValues[1]}/${it.groupValues[2]}" }
        val yr = Regex("""((?:19|20)\d{2})""")
        (yr.find(filename) ?: yr.find(path))?.let { return "${it.groupValues[1]}/unknown" }
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

                val cs = CountingInputStream(contentResolver.openInputStream(zipUri)!!)
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

                        if (sc.rem(50) == 0) {
                            saveAnalyzeState(sc, mediaCount, totalSize, jsonCount)
                            if (sc.rem(500) == 0) saveJsonDateMap()
                            val pct = if (zipBytes > 0) (cs.bytesRead * 100 / zipBytes).toInt() else 0
                            val rdMB = String.format("%.0f", cs.bytesRead / 1048576.0)
                            notifyProgress("분석 $pct% ($rdMB/$zipMB MB) | 파일$sc 미디어$mediaCount",
                                if (zipBytes > 0) (cs.bytesRead / 1048576).toInt() else 0,
                                if (zipBytes > 0) (zipBytes / 1048576).toInt() else 0)
                            if (sc.rem(500) == 0) liveLog("분석 $pct%: ${sc}개 스캔, 미디어 ${mediaCount}, JSON $jsonCount")
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


    // ======== 앨범 정리 (기존 업로드 파일을 앨범 폴더로 복사) ========
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

                // 업로드 완료된 파일 중 앨범이 있는 것만 처리
                val uploaded = getUploadedFiles()
                val albumFiles = albumMap.filter { it.key in uploaded }

                if (albumFiles.isEmpty()) {
                    liveLog("앨범 정리 대상 없음 (업로드된 앨범 파일 없음)")
                    android.os.Handler(android.os.Looper.getMainLooper()).post { organizeCallback?.invoke(0, 0, 0) }
                    stopForeground(STOP_FOREGROUND_DETACH); stopSelf(); return@launch
                }

                val total = albumFiles.size
                var copied = 0; var errors = 0
                liveLog("앨범 정리 시작: ${total}개 파일")
                notifyProgress("앨범 정리 (${total}개)", 0, total)
                progressCallback?.invoke(TakeoutProgress(0, total, 0, false, null))

                // JSON 메타데이터 로드 (날짜 폴더 경로 결정용)
                if (jsonDateMap.isEmpty()) loadJsonDateMap()

                for ((zipPath, albumName) in albumFiles) {
                    if (!isActive) break

                    val fn = zipPath.substringAfterLast('/')
                    val ym = yearMonth(fn, zipPath)
                    val srcPath = "${api.rootFolder}/$ym/$fn"
                    val dstFolder = "${api.rootFolder}/Albums/$albumName"

                    // 대상 폴더 생성
                    var folderOk = false; var folderDone = false
                    api.ensureFolder(dstFolder) { folderOk = it; folderDone = true }
                    while (!folderDone && isActive) delay(100)

                    if (!folderOk) {
                        errors++; copied++
                        liveLog("❌ 폴더 생성 실패: $dstFolder")
                        progressCallback?.invoke(TakeoutProgress(copied, total, errors, false, null))
                        continue
                    }

                    // OneDrive에서 파일 복사
                    var copyOk = false; var copyDone = false
                    api.copyFile(srcPath, dstFolder, fn) { copyOk = it; copyDone = true }
                    while (!copyDone && isActive) delay(100)

                    copied++
                    if (copyOk) {
                        val pct = copied * 100 / total
                        liveLog("📁 $fn → Albums/$albumName")
                        notifyProgress("앨범 정리 $pct% - $fn", copied, total)
                    } else {
                        errors++
                        liveLog("⚠ 복사 실패 (원본 없을 수 있음): $fn")
                    }
                    progressCallback?.invoke(TakeoutProgress(copied, total, errors, false, null))

                }

                val msg = "앨범 정리 완료! 복사:${copied - errors} 실패:$errors (전체:$total)"
                liveLog(msg)
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildDoneNotification("✅ $msg"))
                progressCallback?.invoke(TakeoutProgress(copied, total, errors, true, null))
                android.os.Handler(android.os.Looper.getMainLooper()).post { organizeCallback?.invoke(total, copied - errors, errors) }
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
                    loadJsonDateMap()
                    if (jsonDateMap.isNotEmpty()) {
                        liveLog("저장된 JSON 메타데이터 ${jsonDateMap.size}개 로드 완료")
                    } else {
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

                liveLog("미디어 ${total}개. 업로드 시작...")
                notifyProgress("업로드 시작 (${total}개)", 0, total)
                progressCallback?.invoke(TakeoutProgress(0, total, 0, false, null))

                val uploaded: MutableSet<String>
                if (resume) {
                    uploaded = getUploadedFiles()
                    if (uploaded.isNotEmpty()) liveLog("이어하기: ${uploaded.size}개 스킵")
                } else {
                    // 새 업로드도 기존 완료 목록은 유지 (스킵 가속)
                    uploaded = getUploadedFiles()
                    if (uploaded.isNotEmpty()) liveLog("기존 완료 ${uploaded.size}개는 스킵합니다")
                }

                var done = 0; var errors = 0; var skipped = 0; var doneBytes = 0L
                val zis4 = ZipArchiveInputStream(contentResolver.openInputStream(zipUri))
                var e4 = zis4.nextZipEntry

                while (e4 != null && isActive) {
                    if (!e4.isDirectory && e4.name in mediaNames) {
                        val fn = e4.name.substringAfterLast('/')
                        val albumName = extractAlbumName(e4.name)
                        val fp = if (albumName != null) {
                            "${api.rootFolder}/Albums/$albumName"
                        } else {
                            val ym = yearMonth(fn, e4.name)
                            "${api.rootFolder}/$ym"
                        }

                        if (e4.name in uploaded) {
                            done++; skipped++
                            val pct = if (total > 0) done * 100 / total else 0
                            notifyProgress("이어하기 ($pct%) 스킵: $fn", done, total)
                            progressCallback?.invoke(TakeoutProgress(done, total, errors, false, null, doneBytes, skipped))
                            drain(zis4); e4 = zis4.nextZipEntry; continue
                        }

                        val pathInfo = if (albumName != null) "앨범:$albumName" else yearMonth(fn, e4.name)
                        liveLog("[$done/$total] $fn ($pathInfo)")

                        // 스트리밍 읽기: 4MB 이하는 메모리, 초과는 임시파일
                        val baos = java.io.ByteArrayOutputStream()
                        val tmpFile: java.io.File?
                        val buf = ByteArray(16384)
                        var totalRead = 0L
                        var n = zis4.read(buf)
                        while (n != -1) { baos.write(buf, 0, n); totalRead += n; n = zis4.read(buf) }
                        val fileSize = totalRead
                        val data: ByteArray
                        if (totalRead <= 4 * 1024 * 1024) {
                            data = baos.toByteArray()
                            tmpFile = null
                        } else {
                            tmpFile = java.io.File(cacheDir, "takeout_tmp_${System.currentTimeMillis()}")
                            tmpFile.writeBytes(baos.toByteArray())
                            data = tmpFile.readBytes()
                        }
                        try {

                            var uDone = false; var uOk = false
                            if (fp in createdFolders) {
                                // 폴더 이미 생성됨 - 바로 업로드
                                api.uploadFile(data, fn, fp) { uOk = it; uDone = true }
                            } else {
                                api.ensureFolder(fp) { ok ->
                                    if (ok) {
                                        createdFolders.add(fp)
                                        api.uploadFile(data, fn, fp) { uOk = it; uDone = true }
                                    } else uDone = true
                                }
                            }
                            while (!uDone && isActive) delay(100)

                            if (uOk) {
                                done++; doneBytes += fileSize; uploaded.add(e4.name); addUploadedFile(e4.name)
                                val pct = if (total > 0) done * 100 / total else 0
                                val sizeKB = String.format("%.1f", fileSize / 1024.0); liveLog("✅ $fn (${sizeKB}KB)")
                                notifyProgress("업로드 $pct% - $fn", done, total)
                            } else { done++; errors++; liveLog("❌ $fn") }
                        } finally { tmpFile?.delete() }

                        progressCallback?.invoke(TakeoutProgress(done, total, errors, false, null, doneBytes, skipped))
                    } else { drain(zis4) }
                    e4 = zis4.nextZipEntry
                }
                zis4.close()

                flushUploadedFiles()
                clearUploadedFiles()
                clearMediaList()
                val ok = done - errors - skipped
                val msg = "완료! 성공:$ok 스킵:$skipped 실패:$errors (전체:$total)"
                liveLog(msg)
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildDoneNotification("✅ $msg"))
                progressCallback?.invoke(TakeoutProgress(done, total, errors, true, null, doneBytes, skipped))
                withContext(Dispatchers.Main) { stopForeground(STOP_FOREGROUND_DETACH); stopSelf() }
            } catch (e: CancellationException) {
                flushUploadedFiles()
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

    private fun notifyProgress(msg: String, done: Int, total: Int) {
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

    override fun onDestroy() { super.onDestroy(); job?.cancel(); scope.cancel() }
}

data class TakeoutProgress(
    val done: Int, val total: Int, val errors: Int, val finished: Boolean,
    val errorMessage: String?, val doneBytes: Long = 0L, val skipped: Int = 0
)
