package com.gphotosync

import android.app.*
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.InputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream

class TakeoutUploadService : Service() {

    companion object {
        const val ACTION_START = "com.gphotosync.TAKEOUT_START"
        const val ACTION_STOP = "com.gphotosync.TAKEOUT_STOP"
        const val ACTION_ANALYZE = "com.gphotosync.TAKEOUT_ANALYZE"
        const val ACTION_RESUME = "com.gphotosync.TAKEOUT_RESUME"
        const val EXTRA_ZIP_URI = "zip_uri"
        const val CHANNEL_ID = "takeout_channel"
        const val NOTIF_ID = 2001

        var progressCallback: ((TakeoutProgress) -> Unit)? = null
        var logCallback: ((String) -> Unit)? = null
        var analyzeCallback: ((Int, Long, Int) -> Unit)? = null  // mediaCount, totalSize, scannedCount
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val uriStr = intent.getStringExtra(EXTRA_ZIP_URI) ?: return START_NOT_STICKY
                val startDate = intent.getStringExtra("start_date")
                val endDate = intent.getStringExtra("end_date")
                startForeground(NOTIF_ID, buildNotification("Takeout 업로드 준비 중...", 0, 0))
                startUpload(Uri.parse(uriStr), startDate, endDate)
            }
            ACTION_STOP -> {
                job?.cancel()
                stopSelf()
            }
            ACTION_RESUME -> {
                val uriStr = intent.getStringExtra(EXTRA_ZIP_URI) ?: return START_NOT_STICKY
                val startDate = intent.getStringExtra("start_date")
                val endDate = intent.getStringExtra("end_date")
                startForeground(NOTIF_ID, buildNotification("Takeout 업로드 이어하기...", 0, 0))
                startUpload(Uri.parse(uriStr), startDate, endDate, resume = true)
            }
            ACTION_ANALYZE -> {
                val uriStr = intent.getStringExtra(EXTRA_ZIP_URI) ?: return START_NOT_STICKY
                val startDate = intent.getStringExtra("start_date")
                val endDate = intent.getStringExtra("end_date")
                startForeground(NOTIF_ID, buildNotification("ZIP 파일 분석 중...", 0, 0))
                analyzeZip(Uri.parse(uriStr), startDate, endDate)
            }
        }
        return START_NOT_STICKY
    }

    // === 업로드 진행 상태 저장 (중단 후 이어하기용) ===
    private fun getUploadedFiles(): MutableSet<String> {
        val prefs = getSharedPreferences("takeout_progress", MODE_PRIVATE)
        return prefs.getStringSet("uploaded_files", emptySet())?.toMutableSet() ?: mutableSetOf()
    }

    private fun saveUploadedFile(filename: String) {
        val prefs = getSharedPreferences("takeout_progress", MODE_PRIVATE)
        val set = prefs.getStringSet("uploaded_files", emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add(filename)
        prefs.edit().putStringSet("uploaded_files", set).apply()
    }

    private fun clearUploadedFiles() {
        getSharedPreferences("takeout_progress", MODE_PRIVATE).edit().clear().apply()
    }

    private fun saveUploadState(zipUri: String, done: Int, total: Int, errors: Int, skipped: Int, doneBytes: Long) {
        val prefs = getSharedPreferences("takeout_progress", MODE_PRIVATE)
        prefs.edit()
            .putString("last_zip_uri", zipUri)
            .putInt("last_done", done)
            .putInt("last_total", total)
            .putInt("last_errors", errors)
            .putInt("last_skipped", skipped)
            .putLong("last_done_bytes", doneBytes)
            .apply()
    }

    private fun liveLog(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        try {
            val f = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "sync_log.txt")
            f.appendText("$ts $msg\n")
        } catch (_: Exception) {}
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            logCallback?.invoke("[$ts] $msg")
        }
    }

    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "tiff", "tif", "raw", "cr2", "nef", "arw", "dng", "svg")
    private val videoExtensions = setOf("mp4", "mov", "avi", "mkv", "wmv", "flv", "webm", "m4v", "3gp", "3g2", "mts", "m2ts")

    private fun isMediaFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in imageExtensions || ext in videoExtensions
    }

    // JSON 메타데이터에서 읽은 날짜 맵: 파일명 -> "YYYY/MM"
    private val jsonDateMap = mutableMapOf<String, String>()

    private fun parseJsonMetadata(jsonContent: String): Pair<String, String>? {
        // JSON에서 파일명과 촬영날짜 추출
        try {
            val json = org.json.JSONObject(jsonContent)
            val title = json.optString("title", "")
            
            // photoTakenTime 우선, 없으면 creationTime
            var timestamp = 0L
            if (json.has("photoTakenTime")) {
                timestamp = json.getJSONObject("photoTakenTime").optString("timestamp", "0").toLongOrNull() ?: 0L
            }
            if (timestamp == 0L && json.has("creationTime")) {
                timestamp = json.getJSONObject("creationTime").optString("timestamp", "0").toLongOrNull() ?: 0L
            }
            
            if (title.isNotEmpty() && timestamp > 0) {
                val date = java.util.Date(timestamp * 1000)
                val year = java.text.SimpleDateFormat("yyyy", java.util.Locale.getDefault()).format(date)
                val month = java.text.SimpleDateFormat("MM", java.util.Locale.getDefault()).format(date)
                return Pair(title, "$year/$month")
            }
        } catch (_: Exception) {}
        return null
    }

    private fun extractYearMonth(filename: String, path: String): String {
        // 1순위: JSON 메타데이터
        jsonDateMap[filename]?.let { return it }
        
        // 2순위: 파일명에서 YYYY-MM 또는 YYYYMM 패턴
        val ymPattern = Regex("""((?:19|20)\d{2})[-_]?(\d{2})[-_]?\d{2}""")
        val match = ymPattern.find(filename) ?: ymPattern.find(path)
        if (match != null) {
            return "${match.groupValues[1]}/${match.groupValues[2]}"
        }
        
        // 3순위: 연도만이라도
        val yearPattern = Regex("""((?:19|20)\d{2})""")
        val yearMatch = yearPattern.find(filename) ?: yearPattern.find(path)
        if (yearMatch != null) {
            return "${yearMatch.groupValues[1]}/unknown"
        }
        
        return "unknown"
    }

    private fun extractDateFromName(filename: String, path: String): String? {
        val pattern = Regex("""((?:19|20)\d{2})[\-_]?(\d{2})[\-_]?(\d{2})""")
        val match = pattern.find(filename) ?: pattern.find(path) ?: return null
        return "${match.groupValues[1]}-${match.groupValues[2]}-${match.groupValues[3]}"
    }

    private fun isInDateRange(fileDate: String?, startDate: String?, endDate: String?): Boolean {
        if (startDate == null && endDate == null) return true
        if (fileDate == null) return false
        if (startDate != null && fileDate < startDate) return false
        if (endDate != null && fileDate > endDate) return false
        return true
    }

    private fun analyzeZip(zipUri: Uri, startDate: String?, endDate: String?) {
        job = scope.launch {
            try {
                liveLog("ZIP 파일 분석 시작 - JSON 메타데이터 수집 중...")
                notifyProgress("ZIP 분석 중 (메타데이터 수집)...", 0, 0)
                
                // 1단계: JSON 메타데이터 수집
                jsonDateMap.clear()
                var jsonCount = 0
                try {
                    val jsonInput = contentResolver.openInputStream(zipUri)
                    val jsonZis = ZipArchiveInputStream(jsonInput)
                    var jsonEntry = jsonZis.nextZipEntry
                    while (jsonEntry != null && isActive) {
                        if (!jsonEntry.isDirectory && jsonEntry.name.endsWith(".json")) {
                            try {
                                val jsonBytes = jsonZis.readBytes()
                                val jsonStr = String(jsonBytes, Charsets.UTF_8)
                                val result = parseJsonMetadata(jsonStr)
                                if (result != null) {
                                    jsonDateMap[result.first] = result.second
                                    jsonCount++
                                }
                            } catch (_: Exception) {}
                        }
                        jsonEntry = jsonZis.nextZipEntry
                    }
                    jsonZis.close()
                } catch (e: Exception) {
                    liveLog("JSON 메타데이터 수집 중 오류 (계속 진행): ${e.message}")
                }
                liveLog("JSON 메타데이터 ${jsonCount}개 수집 완료")
                notifyProgress("ZIP 분석 중 (미디어 스캔)...", 0, 0)
                
                // 2단계: 미디어 파일 스캔
                val inputStream = contentResolver.openInputStream(zipUri)
                val zis = ZipArchiveInputStream(inputStream)
                var mediaCount = 0
                var totalSize = 0L
                var scannedCount = 0
                var entry = zis.nextZipEntry

                while (entry != null && isActive) {
                    if (!entry.isDirectory) {
                        scannedCount++
                        if (isMediaFile(entry.name)) {
                            val fname = entry.name.substringAfterLast('/')
                            val fileDate = extractDateFromName(fname, entry.name)
                            if (isInDateRange(fileDate, startDate, endDate)) {
                                mediaCount++
                                val sz = entry.size
                                if (sz > 0) totalSize += sz
                            }
                        }
                        if (scannedCount % 500 == 0) {
                            notifyProgress("ZIP 분석 중... (${scannedCount}개 스캔, 미디어 ${mediaCount}개)", 0, 0)
                            liveLog("분석 중: ${scannedCount}개 스캔, 미디어 ${mediaCount}개 발견")
                        }
                    }
                    entry = zis.nextZipEntry
                }
                zis.close()

                liveLog("분석 완료: 전체 ${scannedCount}개 파일, 미디어 ${mediaCount}개")
                notifyProgress("분석 완료: 미디어 ${mediaCount}개", 0, 0)

                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    analyzeCallback?.invoke(mediaCount, totalSize, scannedCount)
                }

                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            } catch (e: Exception) {
                liveLog("ZIP 분석 실패: ${e.message}")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    analyzeCallback?.invoke(-1, 0L, 0)
                }
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
    }

    private fun startUpload(zipUri: Uri, startDate: String? = null, endDate: String? = null, resume: Boolean = false) {
        val oneDriveApi = OneDriveApi(this)

        job = scope.launch {
            try {
                // JSON 메타데이터가 비어있으면 먼저 수집
                if (jsonDateMap.isEmpty()) {
                    liveLog("JSON 메타데이터 수집 중...")
                    notifyProgress("메타데이터 수집 중...", 0, 0)
                    try {
                        val jsonInput = contentResolver.openInputStream(zipUri)
                        val jsonZis = ZipArchiveInputStream(jsonInput)
                        var jsonEntry = jsonZis.nextZipEntry
                        var jsonCount = 0
                        while (jsonEntry != null && isActive) {
                            if (!jsonEntry.isDirectory && jsonEntry.name.endsWith(".json")) {
                                try {
                                    val jsonBytes = jsonZis.readBytes()
                                    val jsonStr = String(jsonBytes, Charsets.UTF_8)
                                    val result = parseJsonMetadata(jsonStr)
                                    if (result != null) {
                                        jsonDateMap[result.first] = result.second
                                        jsonCount++
                                    }
                                } catch (_: Exception) {}
                            }
                            jsonEntry = jsonZis.nextZipEntry
                        }
                        jsonZis.close()
                        liveLog("JSON 메타데이터 ${jsonCount}개 수집 완료")
                    } catch (e: Exception) {
                        liveLog("JSON 수집 오류 (계속 진행): ${e.message}")
                    }
                }
                
                liveLog("ZIP 파일 분석 중...")

                // 1단계: ZIP 내 미디어 파일 목록 수집
                val mediaEntries = mutableListOf<Pair<String, Long>>() // name, size
                var inputStream: InputStream? = null
                try {
                    inputStream = contentResolver.openInputStream(zipUri)
                    val zis = ZipArchiveInputStream(inputStream)
                    var entry = zis.nextZipEntry
                    while (entry != null && isActive) {
                        if (!entry.isDirectory && isMediaFile(entry.name)) {
                            val fname = entry.name.substringAfterLast('/')
                            val fileDate = extractDateFromName(fname, entry.name)
                            if (isInDateRange(fileDate, startDate, endDate)) {
                                mediaEntries.add(Pair(entry.name, entry.size))
                            }
                        }
                        entry = zis.nextZipEntry
                    }
                    zis.close()
                } catch (e: Exception) {
                    liveLog("ZIP 분석 실패: ${e.message}")
                    progressCallback?.invoke(TakeoutProgress(0, 0, 0, true, "ZIP 분석 실패: ${e.message}"))
                    stopSelf()
                    return@launch
                }

                val total = mediaEntries.size
                if (total == 0) {
                    liveLog("ZIP에 미디어 파일이 없습니다")
                    progressCallback?.invoke(TakeoutProgress(0, 0, 0, true, "미디어 파일 없음"))
                    stopSelf()
                    return@launch
                }

                val rangeMsg = when {
                    startDate != null && endDate != null -> " ($startDate ~ $endDate)"
                    startDate != null -> " ($startDate ~ )"
                    endDate != null -> " ( ~ $endDate)"
                    else -> ""
                }
                liveLog("미디어 파일 ${total}개 발견$rangeMsg. 업로드 시작...")
                notifyProgress("업로드 시작 (${total}개)", 0, total)
                progressCallback?.invoke(TakeoutProgress(0, total, 0, false, null))

                // 2단계: ZIP 다시 열어서 파일별 업로드
                val uploadedFiles = if (resume) getUploadedFiles() else {
                    clearUploadedFiles()
                    mutableSetOf()
                }
                if (resume && uploadedFiles.isNotEmpty()) {
                    liveLog("이어하기: 이전에 완료된 ${uploadedFiles.size}개 파일 스킵")
                }
                var done = 0
                var errors = 0
                var skipped = 0
                var doneBytes = 0L

                val mediaNames = mediaEntries.map { it.first }.toSet()
                inputStream = contentResolver.openInputStream(zipUri)
                val zis = ZipArchiveInputStream(inputStream)
                var entry = zis.nextZipEntry

                while (entry != null && isActive) {
                    if (!entry.isDirectory && entry.name in mediaNames) {
                        val filename = entry.name.substringAfterLast('/')
                        val yearMonth = extractYearMonth(filename, entry.name)
                        val folderPath = "${oneDriveApi.rootFolder}/$yearMonth"

                        // 이어하기: 이미 업로드된 파일 스킵
                        if (entry.name in uploadedFiles) {
                            done++
                            skipped++
                            val pct = if (total > 0) done * 100 / total else 0
                            notifyProgress("이어하기 ($pct%) - 스킵: $filename", done, total)
                            progressCallback?.invoke(TakeoutProgress(done, total, errors, false, null, doneBytes, skipped))
                            // ZIP 스트림에서 데이터를 읽어야 다음 엔트리로 넘어감
                            zis.readBytes()
                            entry = zis.nextZipEntry
                            continue
                        }

                        liveLog("처리 중: $filename ($yearMonth)")

                        // ZIP에서 파일 데이터 읽기
                        val fileData = zis.readBytes()

                        // 중복 체크: OneDrive에 같은 파일명+같은 크기 존재 시 스킵
                        var existingSize: Long? = null
                        var checkDone = false
                        oneDriveApi.checkFileExists("$folderPath/$filename") { size ->
                            existingSize = size
                            checkDone = true
                        }
                        while (!checkDone && isActive) delay(100)

                        if (existingSize != null && existingSize == fileData.size.toLong()) {
                            done++
                            skipped++
                            uploadedFiles.add(entry.name)
                            saveUploadedFile(entry.name)
                            liveLog("⏭ 중복 스킵: $filename (${String.format("%.1f", fileData.size / 1024.0)}KB)")
                            val pct = if (total > 0) done * 100 / total else 0
                            notifyProgress("업로드 중 ($pct%) - 스킵: $filename", done, total)
                            progressCallback?.invoke(TakeoutProgress(done, total, errors, false, null, doneBytes, skipped))
                            entry = zis.nextZipEntry
                            continue
                        }

                        // 폴더 생성 + 업로드
                        var uploadDone = false
                        var uploadOk = false

                        oneDriveApi.ensureFolder(folderPath) { folderOk ->
                            if (folderOk) {
                                oneDriveApi.uploadFile(fileData, filename, folderPath) { ok ->
                                    uploadOk = ok
                                    uploadDone = true
                                }
                            } else {
                                uploadDone = true
                            }
                        }

                        while (!uploadDone && isActive) delay(100)

                        if (uploadOk) {
                            done++
                            doneBytes += fileData.size
                            uploadedFiles.add(entry.name)
                            saveUploadedFile(entry.name)
                            saveUploadState(zipUri.toString(), done, total, errors, skipped, doneBytes)
                            val pct = if (total > 0) done * 100 / total else 0
                            liveLog("✅ 완료: $filename (${String.format("%.1f", fileData.size / 1024.0)}KB)")
                            notifyProgress("업로드 중 ($pct%) - $filename", done, total)
                        } else {
                            done++
                            errors++
                            liveLog("❌ 실패: $filename")
                        }

                        progressCallback?.invoke(TakeoutProgress(done, total, errors, false, null, doneBytes, skipped))
                        delay(300)
                    }
                    entry = zis.nextZipEntry
                }
                zis.close()

                clearUploadedFiles()
                val success = done - errors
                val msg = "Takeout 완료! 성공:${success} 스킵:${skipped} 실패:${errors} (전체:${total})"
                liveLog(msg)
                notifyProgress(msg, done, total)
                progressCallback?.invoke(TakeoutProgress(done, total, errors, true, null, doneBytes, skipped))

                withContext(Dispatchers.Main) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
            } catch (e: CancellationException) {
                liveLog("Takeout 업로드 중단됨 (이어하기 가능)")
                notifyProgress("중단됨 - 이어하기 가능", 0, 0)
                progressCallback?.invoke(TakeoutProgress(0, 0, 0, true, "중단됨 - 이어하기 가능"))
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            } catch (e: Exception) {
                liveLog("오류: ${e.message}")
                progressCallback?.invoke(TakeoutProgress(0, 0, 0, true, e.message))
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
    }

    private fun notifyProgress(message: String, done: Int, total: Int) {
        val notif = buildNotification(message, done, total)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, notif)
    }

    private fun buildNotification(message: String, done: Int, total: Int): Notification {
        val intent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TakeoutUploadService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("📦 Takeout → OneDrive")
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
                CHANNEL_ID, "Takeout 업로드", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Google Takeout → OneDrive 업로드 진행" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
        scope.cancel()
    }
}

data class TakeoutProgress(
    val done: Int,
    val total: Int,
    val errors: Int,
    val finished: Boolean,
    val errorMessage: String?,
    val doneBytes: Long = 0L,
    val skipped: Int = 0
)
