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
        const val EXTRA_ZIP_URI = "zip_uri"
        const val CHANNEL_ID = "takeout_channel"
        const val NOTIF_ID = 2001

        var progressCallback: ((TakeoutProgress) -> Unit)? = null
        var logCallback: ((String) -> Unit)? = null
        var analyzeCallback: ((Int, Long, Int) -> Unit)? = null
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    private val jsonDateMap = mutableMapOf<String, String>()

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
    private fun saveUploadedFile(name: String) {
        val p = getSharedPreferences("takeout_progress", MODE_PRIVATE)
        val s = p.getStringSet("uf", emptySet())?.toMutableSet() ?: mutableSetOf()
        s.add(name); p.edit().putStringSet("uf", s).apply()
    }
    private fun clearUploadedFiles() { getSharedPreferences("takeout_progress", MODE_PRIVATE).edit().clear().apply() }

    private fun liveLog(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        try {
            val f = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "sync_log.txt")
            f.appendText("$ts $msg\n")
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

    private fun yearMonth(filename: String, path: String): String {
        jsonDateMap[filename]?.let { return it }
        val ym = Regex("""((?:19|20)\d{2})[-_]?(\d{2})[-_]?\d{2}""")
        (ym.find(filename) ?: ym.find(path))?.let { return "${'$'}{it.groupValues[1]}/${'$'}{it.groupValues[2]}" }
        val yr = Regex("""((?:19|20)\d{2})""")
        (yr.find(filename) ?: yr.find(path))?.let { return "${'$'}{it.groupValues[1]}/unknown" }
        return "unknown"
    }

    private fun dateFromName(filename: String, path: String): String? {
        val p = Regex("""((?:19|20)\d{2})[-_]?(\d{2})[-_]?(\d{2})""")
        val m = p.find(filename) ?: p.find(path) ?: return null
        return "${'$'}{m.groupValues[1]}-${'$'}{m.groupValues[2]}-${'$'}{m.groupValues[3]}"
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
                    liveLog("분석 이어하기: ${'$'}skipCount개부터 재개 (미디어 ${'$'}mediaCount, JSON ${'$'}jsonCount)")
                } else {
                    clearAnalyzeState(); jsonDateMap.clear()
                    liveLog("ZIP 파일 분석 시작...")
                }

                var zipBytes = 0L
                try { contentResolver.openFileDescriptor(zipUri, "r")?.use { zipBytes = it.statSize } } catch (_: Exception) {}
                val zipMB = if (zipBytes > 0) String.format("%.0f", zipBytes / 1048576.0) else "?"

                val cs = CountingInputStream(contentResolver.openInputStream(zipUri)!!)
                val zis = ZipArchiveInputStream(cs)
                var sc = 0
                var entry = zis.nextZipEntry

                while (entry != null && isActive) {
                    if (!entry.isDirectory) {
                        sc++
                        if (sc <= skipCount) {
                            drain(zis)
                            if (sc % 2000 == 0) notifyProgress("이전 위치로 이동 중... $sc/$skipCount", sc, skipCount)
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
                            if (inRange(fd, startDate, endDate)) { mediaCount++; val sz = entry.size; if (sz > 0) totalSize += sz }
                            drain(zis)
                        } else {
                            drain(zis)
                        }

                        if (sc % 100 == 0) {
                            saveAnalyzeState(sc, mediaCount, totalSize, jsonCount)
                            if (sc % 500 == 0) saveJsonDateMap()
                            val pct = if (zipBytes > 0) (cs.bytesRead * 100 / zipBytes).toInt() else 0
                            val rdMB = String.format("%.0f", cs.bytesRead / 1048576.0)
                            notifyProgress("분석 $pct% ($rdMB/$zipMB MB) | 파일$sc 미디어$mediaCount",
                                if (zipBytes > 0) (cs.bytesRead / 1048576).toInt() else 0,
                                if (zipBytes > 0) (zipBytes / 1048576).toInt() else 0)
                            if (sc % 500 == 0) liveLog("분석 $pct%: $sc개 스캔, 미디어 $mediaCount, JSON $jsonCount")
                            if (zipBytes > 0) progressCallback?.invoke(TakeoutProgress(pct, 100, 0, false, null, cs.bytesRead, 0))
                        }
                    }
                    entry = zis.nextZipEntry
                }
                zis.close()

                if (!isActive) {
                    saveAnalyzeState(sc, mediaCount, totalSize, jsonCount); saveJsonDateMap()
                    liveLog("분석 중단 ($sc개 스캔, 이어하기 가능)")
                    android.os.Handler(android.os.Looper.getMainLooper()).post { analyzeCallback?.invoke(-2, totalSize, sc) }
                    stopForeground(STOP_FOREGROUND_DETACH); stopSelf(); return@launch
                }

                saveJsonDateMap(); clearAnalyzeState()
                liveLog("분석 완료: 전체 $sc개, 미디어 $mediaCount개, JSON $jsonCount개")
                notifyProgress("분석 완료: 미디어 $mediaCount개", 0, 0)
                android.os.Handler(android.os.Looper.getMainLooper()).post { analyzeCallback?.invoke(mediaCount, totalSize, sc) }
                stopForeground(STOP_FOREGROUND_DETACH); stopSelf()
            } catch (e: CancellationException) {
                liveLog("분석 중단됨 (이어하기 가능)")
                android.os.Handler(android.os.Looper.getMainLooper()).post { analyzeCallback?.invoke(-2, 0L, 0) }
                stopForeground(STOP_FOREGROUND_DETACH); stopSelf()
            } catch (e: Exception) {
                liveLog("ZIP 분석 실패: ${'$'}{e.message}")
                android.os.Handler(android.os.Looper.getMainLooper()).post { analyzeCallback?.invoke(-1, 0L, 0) }
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
                    liveLog("JSON $jc개 수집 완료")
                }

                // 미디어 파일 목록 수집
                liveLog("미디어 파일 목록 수집 중...")
                val mediaNames = mutableSetOf<String>()
                val zis3 = ZipArchiveInputStream(contentResolver.openInputStream(zipUri))
                var e3 = zis3.nextZipEntry
                while (e3 != null && isActive) {
                    if (!e3.isDirectory && isMedia(e3.name)) {
                        val fn = e3.name.substringAfterLast('/')
                        if (inRange(dateFromName(fn, e3.name), startDate, endDate)) mediaNames.add(e3.name)
                    }
                    drain(zis3)
                    e3 = zis3.nextZipEntry
                }
                zis3.close()

                val total = mediaNames.size
                if (total == 0) {
                    liveLog("미디어 파일 없음"); progressCallback?.invoke(TakeoutProgress(0,0,0,true,"미디어 없음"))
                    stopSelf(); return@launch
                }

                liveLog("미디어 $total개. 업로드 시작...")
                notifyProgress("업로드 시작 ($total개)", 0, total)
                progressCallback?.invoke(TakeoutProgress(0, total, 0, false, null))

                val uploaded = if (resume) getUploadedFiles() else { clearUploadedFiles(); mutableSetOf() }
                if (resume && uploaded.isNotEmpty()) liveLog("이어하기: ${'$'}{uploaded.size}개 스킵")

                var done = 0; var errors = 0; var skipped = 0; var doneBytes = 0L
                val zis4 = ZipArchiveInputStream(contentResolver.openInputStream(zipUri))
                var e4 = zis4.nextZipEntry

                while (e4 != null && isActive) {
                    if (!e4.isDirectory && e4.name in mediaNames) {
                        val fn = e4.name.substringAfterLast('/')
                        val ym = yearMonth(fn, e4.name)
                        val fp = "${'$'}{api.rootFolder}/$ym"

                        if (e4.name in uploaded) {
                            done++; skipped++
                            val pct = if (total > 0) done * 100 / total else 0
                            notifyProgress("이어하기 ($pct%) 스킵: $fn", done, total)
                            progressCallback?.invoke(TakeoutProgress(done, total, errors, false, null, doneBytes, skipped))
                            drain(zis4); e4 = zis4.nextZipEntry; continue
                        }

                        liveLog("[$done/$total] $fn ($ym)")
                        val data = zis4.readBytes()

                        // 중복 체크
                        var exSize: Long? = null; var ck = false
                        api.checkFileExists("$fp/$fn") { exSize = it; ck = true }
                        while (!ck && isActive) delay(100)

                        if (exSize != null && exSize == data.size.toLong()) {
                            done++; skipped++; uploaded.add(e4.name); saveUploadedFile(e4.name)
                            liveLog("⏭ 중복: $fn")
                            progressCallback?.invoke(TakeoutProgress(done, total, errors, false, null, doneBytes, skipped))
                            e4 = zis4.nextZipEntry; continue
                        }

                        var uDone = false; var uOk = false
                        api.ensureFolder(fp) { ok ->
                            if (ok) api.uploadFile(data, fn, fp) { uOk = it; uDone = true }
                            else uDone = true
                        }
                        while (!uDone && isActive) delay(100)

                        if (uOk) {
                            done++; doneBytes += data.size; uploaded.add(e4.name); saveUploadedFile(e4.name)
                            val pct = if (total > 0) done * 100 / total else 0
                            liveLog("✅ $fn (${'$'}{String.format("%.1f", data.size/1024.0)}KB)")
                            notifyProgress("업로드 $pct% - $fn", done, total)
                        } else { done++; errors++; liveLog("❌ $fn") }

                        progressCallback?.invoke(TakeoutProgress(done, total, errors, false, null, doneBytes, skipped))
                        delay(200)
                    } else { drain(zis4) }
                    e4 = zis4.nextZipEntry
                }
                zis4.close()

                clearUploadedFiles()
                val ok = done - errors - skipped
                val msg = "완료! 성공:$ok 스킵:$skipped 실패:$errors (전체:$total)"
                liveLog(msg); notifyProgress(msg, done, total)
                progressCallback?.invoke(TakeoutProgress(done, total, errors, true, null, doneBytes, skipped))
                withContext(Dispatchers.Main) { stopForeground(STOP_FOREGROUND_DETACH); stopSelf() }
            } catch (e: CancellationException) {
                liveLog("업로드 중단됨 (이어하기 가능)")
                progressCallback?.invoke(TakeoutProgress(0,0,0,true,"중단됨 - 이어하기 가능"))
                stopForeground(STOP_FOREGROUND_DETACH); stopSelf()
            } catch (e: Exception) {
                liveLog("오류: ${'$'}{e.message}")
                progressCallback?.invoke(TakeoutProgress(0,0,0,true,e.message))
                stopForeground(STOP_FOREGROUND_DETACH); stopSelf()
            }
        }
    }

    private fun notifyProgress(msg: String, done: Int, total: Int) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification(msg, done, total))
    }

    private fun buildNotification(msg: String, done: Int, total: Int): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val si = PendingIntent.getService(this, 1, Intent(this, TakeoutUploadService::class.java).apply { action = ACTION_STOP }, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("📦 Takeout → OneDrive").setContentText(msg)
            .setSmallIcon(android.R.drawable.ic_menu_upload).setContentIntent(pi)
            .addAction(android.R.drawable.ic_media_pause, "중단", si)
            .setProgress(total, done, total == 0).setOngoing(true).setOnlyAlertOnce(true).build()
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
