package com.gphotosync

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.tabs.TabLayout
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File
import java.io.FileWriter
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import android.app.DatePickerDialog
import java.util.Calendar
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var progressDb: SyncProgressStore
    private var isSyncing = false
    private val handler = Handler(Looper.getMainLooper())
    private var liveLogLines = mutableListOf<String>()

    // Tab views
    private var syncView: View? = null
    private var authView: View? = null
    private var infoView: View? = null
    private var takeoutView: View? = null
    private var selectedZipUri: android.net.Uri? = null
    private var takeoutLogLines = mutableListOf<String>()
    private var filterStartDate: String? = null
    private var filterEndDate: String? = null

    private val googleAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(this, "Google 인증 성공!", Toast.LENGTH_SHORT).show()
        }
        updateAuthUI()
    }

    private val msAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(this, "Microsoft 인증 성공!", Toast.LENGTH_SHORT).show()
        }
        updateAuthUI()
    }

    private val jsonFilePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { parseGoogleJson(it) }
    }

    private val authJsonPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { importAuthFromJson(it) }
    }

    private val zipFilePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onZipSelected(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TokenManager.init(this)
        applySavedTheme()
        setContentView(R.layout.activity_main)
        progressDb = SyncProgressStore(this)

        setupTabs()

        requestNotificationPermission()

        SyncForegroundService.progressCallback = { progress ->
            runOnUiThread { updateProgress(progress) }
        }
        SyncForegroundService.logCallback = { line ->
            runOnUiThread { appendLiveLog(line) }
        }

        TakeoutUploadService.progressCallback = { progress ->
            runOnUiThread { updateTakeoutProgress(progress) }
        }
        TakeoutUploadService.logCallback = { line ->
            runOnUiThread { appendTakeoutLog(line) }
        }
    }

    private fun applySavedTheme() {
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        when (prefs.getInt("theme", 0)) {
            0 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            1 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            2 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
    }

    private fun setupTabs() {
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        val contentFrame = findViewById<android.widget.FrameLayout>(R.id.contentFrame)

        tabLayout.addTab(tabLayout.newTab().setText("동기화"))
        tabLayout.addTab(tabLayout.newTab().setText("인증"))
        tabLayout.addTab(tabLayout.newTab().setText("정보"))
        tabLayout.addTab(tabLayout.newTab().setText("Takeout"))

        syncView = LayoutInflater.from(this).inflate(R.layout.tab_sync, contentFrame, false)
        authView = LayoutInflater.from(this).inflate(R.layout.tab_auth, contentFrame, false)
        infoView = LayoutInflater.from(this).inflate(R.layout.tab_info, contentFrame, false)
        takeoutView = LayoutInflater.from(this).inflate(R.layout.tab_takeout, contentFrame, false)

        setupSyncTab()
        setupAuthTab()
        setupInfoTab()
        setupTakeoutTab()

        contentFrame.addView(syncView)

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                contentFrame.removeAllViews()
                when (tab.position) {
                    0 -> { contentFrame.addView(syncView); loadHistorySummary() }
                    1 -> { contentFrame.addView(authView); updateAuthUI() }
                    2 -> contentFrame.addView(infoView)
                    3 -> contentFrame.addView(takeoutView)
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    // ======== SYNC TAB ========
    private fun setupSyncTab() {
        val v = syncView ?: return

        v.findViewById<android.widget.Button>(R.id.btnSync).setOnClickListener {
            if (isSyncing) {
                val stopIntent = Intent(this, SyncForegroundService::class.java)
                stopIntent.action = SyncForegroundService.ACTION_STOP
                startForegroundService(stopIntent)
                isSyncing = false
                v.findViewById<android.widget.Button>(R.id.btnSync).text = "동기화 시작"
                v.findViewById<android.widget.Button>(R.id.btnSync).setBackgroundColor(0xFF2E7D32.toInt())
            } else {
                if (!TokenManager.isGoogleAuthed() || !TokenManager.isMicrosoftAuthed()) {
                    Toast.makeText(this, "먼저 인증 탭에서 로그인하세요", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                startSync()
            }
        }

        v.findViewById<android.widget.Button>(R.id.btnRetryFailed).setOnClickListener {
            retryFailed()
        }

        v.findViewById<android.widget.Button>(R.id.btnShowDetail)?.setOnClickListener {
            v.findViewById<View>(R.id.cardDetails).visibility = View.VISIBLE
            refreshDetailLists()
        }

        v.findViewById<android.widget.Button>(R.id.btnHideDetail)?.setOnClickListener {
            v.findViewById<View>(R.id.cardDetails).visibility = View.GONE
        }

        v.findViewById<android.widget.Button>(R.id.btnTabSuccess)?.setOnClickListener {
            v.findViewById<View>(R.id.layoutSuccess).visibility = View.VISIBLE
            v.findViewById<View>(R.id.layoutFailed).visibility = View.GONE
            v.findViewById<android.widget.Button>(R.id.btnTabSuccess).setBackgroundColor(0xFF4CAF50.toInt())
            v.findViewById<android.widget.Button>(R.id.btnTabFailed).setBackgroundColor(0xFF9E9E9E.toInt())
            refreshDetailLists()
        }

        v.findViewById<android.widget.Button>(R.id.btnTabFailed)?.setOnClickListener {
            v.findViewById<View>(R.id.layoutSuccess).visibility = View.GONE
            v.findViewById<View>(R.id.layoutFailed).visibility = View.VISIBLE
            v.findViewById<android.widget.Button>(R.id.btnTabSuccess).setBackgroundColor(0xFF9E9E9E.toInt())
            v.findViewById<android.widget.Button>(R.id.btnTabFailed).setBackgroundColor(0xFFF44336.toInt())
            refreshDetailLists()
        }

        loadHistorySummary()
        updateRetryButton()
    }

    private fun startSync() {
        isSyncing = true
        liveLogLines.clear()
        val v = syncView ?: return
        v.findViewById<TextView>(R.id.tvLiveLog).text = ""
        v.findViewById<android.widget.Button>(R.id.btnSync).text = "동기화 중단"
        v.findViewById<android.widget.Button>(R.id.btnSync).setBackgroundColor(0xFFF44336.toInt())
        v.findViewById<android.widget.ProgressBar>(R.id.progressBar).visibility = View.VISIBLE
        v.findViewById<TextView>(R.id.tvProgressDetail).visibility = View.VISIBLE
        v.findViewById<View>(R.id.layoutStats).visibility = View.VISIBLE
        v.findViewById<android.widget.ProgressBar>(R.id.progressBar).isIndeterminate = true

        val intent = Intent(this, SyncForegroundService::class.java)
        intent.action = SyncForegroundService.ACTION_START
        startForegroundService(intent)
    }

    private fun retryFailed() {
        val failedRecords = progressDb.getFailedRecords()
        if (failedRecords.isEmpty()) {
            Toast.makeText(this, "재시도할 항목이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }

        isSyncing = true
        liveLogLines.clear()
        val v = syncView ?: return
        v.findViewById<TextView>(R.id.tvLiveLog).text = ""
        v.findViewById<android.widget.Button>(R.id.btnSync).text = "동기화 중단"
        v.findViewById<android.widget.Button>(R.id.btnSync).setBackgroundColor(0xFFF44336.toInt())
        v.findViewById<android.widget.ProgressBar>(R.id.progressBar).visibility = View.VISIBLE
        v.findViewById<android.widget.ProgressBar>(R.id.progressBar).isIndeterminate = true
        v.findViewById<TextView>(R.id.tvProgressDetail).visibility = View.VISIBLE
        v.findViewById<View>(R.id.layoutStats).visibility = View.VISIBLE

        SyncForegroundService.retryItems = failedRecords
        val intent = Intent(this, SyncForegroundService::class.java)
        intent.action = SyncForegroundService.ACTION_RETRY
        startForegroundService(intent)
    }

    private fun appendLiveLog(line: String) {
        liveLogLines.add(line)
        if (liveLogLines.size > 50) liveLogLines.removeAt(0)
        val v = syncView ?: return
        val tv = v.findViewById<TextView>(R.id.tvLiveLog)
        tv.text = liveLogLines.joinToString("\n")
        val sv = v.findViewById<ScrollView>(R.id.scrollLiveLog)
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }
    }

    private fun updateProgress(progress: SyncProgress) {
        val v = syncView ?: return
        val pb = v.findViewById<android.widget.ProgressBar>(R.id.progressBar)
        val tvDetail = v.findViewById<TextView>(R.id.tvProgressDetail)

        pb.visibility = View.VISIBLE
        tvDetail.visibility = View.VISIBLE
        v.findViewById<View>(R.id.layoutStats).visibility = View.VISIBLE

        if (progress.total > 0) {
            pb.isIndeterminate = false
            pb.max = progress.total
            pb.progress = progress.done
            val pct = progress.done * 100 / progress.total
            val totalMB = String.format("%.1f", progress.totalBytes / 1024.0 / 1024.0)
            val doneMB = String.format("%.1f", progress.doneBytes / 1024.0 / 1024.0)
            tvDetail.text = "${progress.done}/${progress.total} ($pct%) | ${doneMB}MB / ${totalMB}MB"
        }

        v.findViewById<TextView>(R.id.tvTotal).text = "전체: ${progress.total}"
        val successCount = progress.done - progress.errors - progress.skipped
        v.findViewById<TextView>(R.id.tvDone).text = "완료: $successCount"
        v.findViewById<TextView>(R.id.tvSkipped).text = "스킵: ${progress.skipped}"
        v.findViewById<TextView>(R.id.tvErrors).text = "실패: ${progress.errors}"

        if (progress.finished) {
            isSyncing = false
            v.findViewById<android.widget.Button>(R.id.btnSync).text = "동기화 시작"
            v.findViewById<android.widget.Button>(R.id.btnSync).setBackgroundColor(0xFF2E7D32.toInt())
            if (progress.errorMessage != null) {
                v.findViewById<TextView>(R.id.tvStatus).text = "오류: ${progress.errorMessage}"
            } else {
                v.findViewById<TextView>(R.id.tvStatus).text = "동기화 완료! 성공:$successCount 스킵:${progress.skipped} 실패:${progress.errors}"
            }
            saveHistorySummary(progress)
            loadHistorySummary()
            refreshDetailLists()
            updateRetryButton()
        } else {
            v.findViewById<TextView>(R.id.tvStatus).text = "동기화 중..."
        }
    }

    private fun updateRetryButton() {
        val v = syncView ?: return
        val btn = v.findViewById<android.widget.Button>(R.id.btnRetryFailed)
        val failedCount = progressDb.getFailedRecords().size
        if (failedCount > 0) {
            btn.visibility = View.VISIBLE
            btn.text = "🔄 실패 항목 재시도 (${failedCount}건)"
        } else {
            btn.visibility = View.GONE
        }
    }

    private fun refreshDetailLists() {
        val v = syncView ?: return
        val sid = selectedSessionId
        val successRecords = if (sid != null) progressDb.getSuccessRecordsBySession(sid) else progressDb.getSuccessRecords()
        val failedRecords = if (sid != null) progressDb.getFailedRecordsBySession(sid) else progressDb.getFailedRecords()
        val dateFormat = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())

        v.findViewById<TextView>(R.id.tvSuccessCount).text = "완료: ${successRecords.size}건"
        v.findViewById<TextView>(R.id.tvSuccessList).text = if (successRecords.isEmpty()) "내역 없음"
        else successRecords.take(200).joinToString("\n") { r ->
            val time = dateFormat.format(Date(r.timestamp))
            val size = if (r.fileSize > 0) formatSize(r.fileSize) else ""
            "[$time] ${r.filename} $size"
        }

        v.findViewById<TextView>(R.id.tvFailedCount).text = "실패: ${failedRecords.size}건"
        v.findViewById<TextView>(R.id.tvFailedList).text = if (failedRecords.isEmpty()) "내역 없음"
        else failedRecords.joinToString("\n") { r ->
            val time = dateFormat.format(Date(r.timestamp))
            "[$time] ${r.filename}\n  > ${r.error}"
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format("%.1fMB", bytes / 1024.0 / 1024.0)
            bytes >= 1024 -> String.format("%.0fKB", bytes / 1024.0)
            else -> "${bytes}B"
        }
    }

    private fun saveHistorySummary(progress: SyncProgress) {
        val prefs = getSharedPreferences("sync_history", MODE_PRIVATE)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val now = fmt.format(Date())
        val success = progress.done - progress.errors - progress.skipped
        val totalMB = String.format("%.1f", progress.totalBytes / 1024.0 / 1024.0)
        val sessionId = if (progress.sessionId.isNotEmpty()) progress.sessionId else now
        val entry = "$sessionId | 성공:$success 스킵:${progress.skipped} 실패:${progress.errors} (전체:${progress.total}, ${totalMB}MB)"
        val history = prefs.getStringSet("entries", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        history.add(entry)
        prefs.edit().putStringSet("entries", history).putString("latest", entry).apply()
    }

    private var historyEntries = listOf<String>()
    private var selectedSessionId: String? = null

    private fun loadHistorySummary() {
        val v = syncView ?: return
        val prefs = getSharedPreferences("sync_history", MODE_PRIVATE)
        val history = prefs.getStringSet("entries", emptySet()) ?: emptySet()
        val card = v.findViewById<View>(R.id.cardHistory)
        val lv = v.findViewById<ListView>(R.id.lvHistory)

        if (history.isNotEmpty()) {
            card.visibility = View.VISIBLE
            historyEntries = history.sortedDescending().take(20)
            val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_activated_1, historyEntries)
            lv.adapter = adapter
            lv.choiceMode = ListView.CHOICE_MODE_SINGLE
            lv.setOnItemClickListener { _, _, position, _ ->
                lv.setItemChecked(position, true)
                val selected = historyEntries[position]
                selectedSessionId = selected.substringBefore(" |").trim()
                refreshDetailLists()
            }
        }

        val successCount = progressDb.getSuccessRecords().size
        val failedCount = progressDb.getFailedRecords().size
        if (successCount > 0 || failedCount > 0) {
            card.visibility = View.VISIBLE
        }
    }

    // ======== AUTH TAB ========
    private fun setupAuthTab() {
        val v = authView ?: return

        v.findViewById<android.widget.Button>(R.id.btnGoogleSetup).setOnClickListener { showGoogleSetupDialog() }
        v.findViewById<android.widget.Button>(R.id.btnGoogleAuth).setOnClickListener {
            if (TokenManager.get(TokenManager.KEY_G_CLIENT_ID).isNullOrEmpty()) {
                Toast.makeText(this, "먼저 API 설정을 하세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(this, OAuthActivity::class.java)
            intent.putExtra(OAuthActivity.EXTRA_TYPE, "google")
            googleAuthLauncher.launch(intent)
        }
        v.findViewById<android.widget.Button>(R.id.btnMsSetup).setOnClickListener { showMsSetupDialog() }
        v.findViewById<android.widget.Button>(R.id.btnMsAuth).setOnClickListener {
            if (TokenManager.get(TokenManager.KEY_MS_CLIENT_ID).isNullOrEmpty()) {
                Toast.makeText(this, "먼저 API 설정을 하세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(this, OAuthActivity::class.java)
            intent.putExtra(OAuthActivity.EXTRA_TYPE, "microsoft")
            msAuthLauncher.launch(intent)
        }
        v.findViewById<android.widget.Button>(R.id.btnExportAuth).setOnClickListener { exportAuthToJson() }
        v.findViewById<android.widget.Button>(R.id.btnImportAuth).setOnClickListener { authJsonPicker.launch("*/*") }

        v.findViewById<android.widget.Button>(R.id.btnReset).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("초기화")
                .setMessage("모든 인증 정보와 동기화 기록을 삭제합니다.")
                .setPositiveButton("확인") { _, _ ->
                    TokenManager.clearAll()
                    progressDb.reset()
                    getSharedPreferences("sync_history", MODE_PRIVATE).edit().clear().apply()
                    updateAuthUI()
                    Toast.makeText(this, "초기화 완료", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("취소", null)
                .show()
        }
        updateAuthUI()
    }

    private fun updateAuthUI() {
        val v = authView ?: return
        val gId = TokenManager.get(TokenManager.KEY_G_CLIENT_ID)
        val gToken = TokenManager.get(TokenManager.KEY_G_ACCESS)
        v.findViewById<TextView>(R.id.tvGoogleClientId).text =
            if (!gId.isNullOrEmpty()) "Client ID: ${gId.take(20)}..." else "Client ID 미설정"
        v.findViewById<TextView>(R.id.tvGoogleStatus).text =
            if (TokenManager.isGoogleAuthed()) "✅ 인증 완료" else "❌ 미인증"
        v.findViewById<android.widget.Button>(R.id.btnGoogleAuth).text =
            if (!gToken.isNullOrEmpty()) "재인증" else "로그인"

        val msId = TokenManager.get(TokenManager.KEY_MS_CLIENT_ID)
        val msToken = TokenManager.get(TokenManager.KEY_MS_ACCESS)
        v.findViewById<TextView>(R.id.tvMsClientId).text =
            if (!msId.isNullOrEmpty()) "Client ID: ${msId.take(20)}..." else "Client ID 미설정"
        v.findViewById<TextView>(R.id.tvMsStatus).text =
            if (TokenManager.isMicrosoftAuthed()) "✅ 인증 완료" else "❌ 미인증"
        v.findViewById<android.widget.Button>(R.id.btnMsAuth).text =
            if (!msToken.isNullOrEmpty()) "재인증" else "로그인"
    }

    // ======== TAKEOUT TAB ========
    private fun setupTakeoutTab() {
        val v = takeoutView ?: return

        v.findViewById<android.widget.Button>(R.id.btnOpenTakeout).setOnClickListener {
            val url = "https://takeout.google.com/settings/takeout/custom/photo"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        v.findViewById<android.widget.Button>(R.id.btnSelectZip).setOnClickListener {
            zipFilePicker.launch("application/zip")
        }

        v.findViewById<android.widget.Button>(R.id.btnStartTakeout).setOnClickListener {
            val uri = selectedZipUri
            if (uri == null) {
                Toast.makeText(this, "ZIP 파일을 먼저 선택하세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!TokenManager.isMicrosoftAuthed()) {
                Toast.makeText(this, "먼저 인증 탭에서 Microsoft 로그인하세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startTakeoutUpload(uri)
        }

        v.findViewById<android.widget.Button>(R.id.btnStopTakeout).setOnClickListener {
            val stopIntent = Intent(this, TakeoutUploadService::class.java)
            stopIntent.action = TakeoutUploadService.ACTION_STOP
            startForegroundService(stopIntent)
            v.findViewById<android.widget.Button>(R.id.btnStopTakeout).visibility = View.GONE
            v.findViewById<android.widget.Button>(R.id.btnStartTakeout).isEnabled = true
            v.findViewById<android.widget.Button>(R.id.btnStartTakeout).text = "🚀 OneDrive에 업로드"
        }

        v.findViewById<android.widget.Button>(R.id.btnStartDate).setOnClickListener {
            showDatePicker(true)
        }
        v.findViewById<android.widget.Button>(R.id.btnEndDate).setOnClickListener {
            showDatePicker(false)
        }
        v.findViewById<android.widget.Button>(R.id.btnClearDate).setOnClickListener {
            filterStartDate = null
            filterEndDate = null
            v.findViewById<android.widget.Button>(R.id.btnStartDate).text = "시작일 선택"
            v.findViewById<android.widget.Button>(R.id.btnEndDate).text = "종료일 선택"
            v.findViewById<TextView>(R.id.tvDateRange).text = "전체 기간 (필터 없음)"
            selectedZipUri?.let { onZipSelected(it) }
        }
    }

    private fun onZipSelected(uri: Uri) {
        selectedZipUri = uri
        val v = takeoutView ?: return

        // ZIP 파일 정보 표시
        Thread {
            try {
                val input = contentResolver.openInputStream(uri)
                val zis = ZipArchiveInputStream(input)
                var mediaCount = 0
                var totalSize = 0L
                val imageExt = setOf("jpg","jpeg","png","gif","bmp","webp","heic","heif","tiff","tif","raw","cr2","nef","arw","dng")
                val videoExt = setOf("mp4","mov","avi","mkv","wmv","flv","webm","m4v","3gp")
                var entry = zis.nextZipEntry
                val startD = filterStartDate
                val endD = filterEndDate
                val yearPattern = Regex("""((?:19|20)\d{2})[\-_]?(\d{2})[\-_]?(\d{2})""")
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val ext = entry.name.substringAfterLast('.', "").lowercase()
                        if (ext in imageExt || ext in videoExt) {
                            val fname = entry.name.substringAfterLast('/')
                            val match = yearPattern.find(fname) ?: yearPattern.find(entry.name)
                            val fileDate = if (match != null) "${match.groupValues[1]}-${match.groupValues[2]}-${match.groupValues[3]}" else null
                            val inRange = when {
                                fileDate == null -> startD == null && endD == null
                                startD != null && fileDate < startD -> false
                                endD != null && fileDate > endD -> false
                                else -> true
                            }
                            if (inRange) {
                                mediaCount++
                                totalSize += entry.size
                            }
                        }
                    }
                    entry = zis.nextZipEntry
                }
                zis.close()
                val sizeMB = String.format("%.1f", totalSize / 1024.0 / 1024.0)
                runOnUiThread {
                    v.findViewById<TextView>(R.id.tvZipInfo).text = "미디어 파일: ${mediaCount}개 (약 ${sizeMB}MB)"
                    v.findViewById<android.widget.Button>(R.id.btnStartTakeout).isEnabled = mediaCount > 0
                }
            } catch (e: Exception) {
                runOnUiThread {
                    v.findViewById<TextView>(R.id.tvZipInfo).text = "ZIP 분석 실패: ${e.message}"
                }
            }
        }.start()
    }

    private fun startTakeoutUpload(uri: Uri) {
        takeoutLogLines.clear()
        val v = takeoutView ?: return
        v.findViewById<TextView>(R.id.tvTakeoutLog).text = ""
        v.findViewById<android.widget.Button>(R.id.btnStartTakeout).isEnabled = false
        v.findViewById<android.widget.Button>(R.id.btnStopTakeout).visibility = View.VISIBLE
        v.findViewById<android.widget.ProgressBar>(R.id.takeoutProgressBar).visibility = View.VISIBLE
        v.findViewById<android.widget.ProgressBar>(R.id.takeoutProgressBar).isIndeterminate = true
        v.findViewById<TextView>(R.id.tvTakeoutProgress).visibility = View.VISIBLE
        v.findViewById<TextView>(R.id.tvTakeoutStatus).text = "업로드 시작..."

        val intent = Intent(this, TakeoutUploadService::class.java)
        intent.action = TakeoutUploadService.ACTION_START
        intent.putExtra(TakeoutUploadService.EXTRA_ZIP_URI, uri.toString())
        if (filterStartDate != null) intent.putExtra("start_date", filterStartDate)
        if (filterEndDate != null) intent.putExtra("end_date", filterEndDate)
        startForegroundService(intent)
    }

    private fun updateTakeoutProgress(progress: TakeoutProgress) {
        val v = takeoutView ?: return
        val pb = v.findViewById<android.widget.ProgressBar>(R.id.takeoutProgressBar)
        val tvProgress = v.findViewById<TextView>(R.id.tvTakeoutProgress)
        val tvStatus = v.findViewById<TextView>(R.id.tvTakeoutStatus)

        if (progress.total > 0) {
            pb.isIndeterminate = false
            pb.max = progress.total
            pb.progress = progress.done
            val pct = progress.done * 100 / progress.total
            val doneMB = String.format("%.1f", progress.doneBytes / 1024.0 / 1024.0)
            tvProgress.text = "${progress.done}/${progress.total} ($pct%) | ${doneMB}MB"
        }

        if (progress.finished) {
            v.findViewById<android.widget.Button>(R.id.btnStopTakeout).visibility = View.GONE
            v.findViewById<android.widget.Button>(R.id.btnStartTakeout).isEnabled = true
            v.findViewById<android.widget.Button>(R.id.btnStartTakeout).text = "🚀 OneDrive에 업로드"
            val success = progress.done - progress.errors - progress.skipped
            if (progress.errorMessage != null) {
                tvStatus.text = "오류: ${progress.errorMessage}"
            } else {
                tvStatus.text = "완료! 성공:${success} 스킵:${progress.skipped} 실패:${progress.errors}"
            }
        } else {
            tvStatus.text = "업로드 중..."
        }
    }

    private fun showDatePicker(isStart: Boolean) {
        val cal = Calendar.getInstance()
        DatePickerDialog(this, { _, year, month, day ->
            val date = String.format("%04d-%02d-%02d", year, month + 1, day)
            val v = takeoutView ?: return@DatePickerDialog
            if (isStart) {
                filterStartDate = date
                v.findViewById<android.widget.Button>(R.id.btnStartDate).text = date
            } else {
                filterEndDate = date
                v.findViewById<android.widget.Button>(R.id.btnEndDate).text = date
            }
            val start = filterStartDate ?: "처음"
            val end = filterEndDate ?: "끝"
            v.findViewById<TextView>(R.id.tvDateRange).text = "$start ~ $end"
            selectedZipUri?.let { onZipSelected(it) }
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun appendTakeoutLog(line: String) {
        takeoutLogLines.add(line)
        if (takeoutLogLines.size > 50) takeoutLogLines.removeAt(0)
        val v = takeoutView ?: return
        val tv = v.findViewById<TextView>(R.id.tvTakeoutLog)
        tv.text = takeoutLogLines.joinToString("\n")
        val sv = v.findViewById<ScrollView>(R.id.scrollTakeoutLog)
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }
    }

    // ======== INFO TAB ========
    private fun setupInfoTab() {
        val v = infoView ?: return
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        val current = prefs.getInt("theme", 0)

        val rg = v.findViewById<RadioGroup>(R.id.rgTheme)
        when (current) {
            0 -> v.findViewById<RadioButton>(R.id.rbSystem).isChecked = true
            1 -> v.findViewById<RadioButton>(R.id.rbLight).isChecked = true
            2 -> v.findViewById<RadioButton>(R.id.rbDark).isChecked = true
        }

        rg.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbLight -> 1
                R.id.rbDark -> 2
                else -> 0
            }
            prefs.edit().putInt("theme", mode).apply()
            when (mode) {
                0 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                1 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                2 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            }
        }
    }

    // ======== DIALOGS ========
    private fun showGoogleSetupDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val etId = EditText(this).apply {
            hint = "Google Client ID"
            setText(TokenManager.get(TokenManager.KEY_G_CLIENT_ID) ?: "")
        }
        val etSecret = EditText(this).apply {
            hint = "Google Client Secret"
            setText(TokenManager.get(TokenManager.KEY_G_CLIENT_SECRET) ?: "")
        }
        layout.addView(etId)
        layout.addView(etSecret)
        layout.addView(android.widget.Button(this).apply {
            text = "JSON 파일에서 가져오기"
            setOnClickListener { jsonFilePicker.launch("*/*") }
        })

        AlertDialog.Builder(this)
            .setTitle("Google API 설정")
            .setView(layout)
            .setPositiveButton("저장") { _, _ ->
                val id = etId.text.toString().trim()
                val secret = etSecret.text.toString().trim()
                if (id.isNotEmpty()) {
                    TokenManager.save(TokenManager.KEY_G_CLIENT_ID, id)
                    if (secret.isNotEmpty()) TokenManager.save(TokenManager.KEY_G_CLIENT_SECRET, secret)
                    updateAuthUI()
                    Toast.makeText(this, "저장 완료", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showMsSetupDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val etId = EditText(this).apply {
            hint = "Microsoft Client ID"
            setText(TokenManager.get(TokenManager.KEY_MS_CLIENT_ID) ?: "")
        }
        layout.addView(etId)

        layout.addView(TextView(this).apply {
            text = "\n--- rclone 토큰 직접 입력 (선택) ---"
            setPadding(0, 24, 0, 8)
        })
        val etToken = EditText(this).apply {
            hint = "rclone authorize 결과 JSON 붙여넣기"
            minLines = 3
            maxLines = 5
        }
        layout.addView(etToken)

        AlertDialog.Builder(this)
            .setTitle("Microsoft API 설정")
            .setView(layout)
            .setPositiveButton("저장") { _, _ ->
                val id = etId.text.toString().trim()
                val tokenJson = etToken.text.toString().trim()
                if (id.isNotEmpty()) {
                    TokenManager.save(TokenManager.KEY_MS_CLIENT_ID, id)
                }
                if (tokenJson.isNotEmpty()) {
                    try {
                        val json = org.json.JSONObject(tokenJson)
                        val access = json.getString("access_token")
                        val refresh = json.optString("refresh_token", "")
                        val expiresIn = json.optLong("expires_in", 3600)
                        TokenManager.save(TokenManager.KEY_MS_ACCESS, access)
                        if (refresh.isNotEmpty()) TokenManager.save(TokenManager.KEY_MS_REFRESH, refresh)
                        TokenManager.saveLong(TokenManager.KEY_MS_EXPIRY, System.currentTimeMillis() / 1000 + expiresIn)
                        Toast.makeText(this, "토큰 저장 완료!", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "JSON 파싱 실패: " + e.message, Toast.LENGTH_LONG).show()
                    }
                }
                if (id.isEmpty() && tokenJson.isEmpty()) {
                    Toast.makeText(this, "입력값 없음", Toast.LENGTH_SHORT).show()
                }
                updateAuthUI()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun exportAuthToJson() {
        val json = JSONObject()

        // Google
        val google = JSONObject()
        TokenManager.get(TokenManager.KEY_G_CLIENT_ID)?.let { google.put("client_id", it) }
        TokenManager.get(TokenManager.KEY_G_CLIENT_SECRET)?.let { google.put("client_secret", it) }
        TokenManager.get(TokenManager.KEY_G_ACCESS)?.let { google.put("access_token", it) }
        TokenManager.get(TokenManager.KEY_G_REFRESH)?.let { google.put("refresh_token", it) }
        TokenManager.getLong(TokenManager.KEY_G_EXPIRY).let { if (it > 0) google.put("expires_at", it) }
        json.put("google", google)

        // Microsoft
        val ms = JSONObject()
        TokenManager.get(TokenManager.KEY_MS_CLIENT_ID)?.let { ms.put("client_id", it) }
        TokenManager.get(TokenManager.KEY_MS_ACCESS)?.let { ms.put("access_token", it) }
        TokenManager.get(TokenManager.KEY_MS_REFRESH)?.let { ms.put("refresh_token", it) }
        TokenManager.getLong(TokenManager.KEY_MS_EXPIRY).let { if (it > 0) ms.put("expires_at", it) }
        json.put("microsoft", ms)

        try {
            val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val file = File(dir, "gphotosync_auth.json")
            FileWriter(file).use { it.write(json.toString(2)) }
            Toast.makeText(this, "내보내기 완료: Download/gphotosync_auth.json", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "내보내기 실패: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun importAuthFromJson(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val jsonStr = inputStream?.bufferedReader()?.readText() ?: return
            val json = JSONObject(jsonStr)

            var count = 0

            if (json.has("google")) {
                val g = json.getJSONObject("google")
                g.optString("client_id", "").let { if (it.isNotEmpty()) { TokenManager.save(TokenManager.KEY_G_CLIENT_ID, it); count++ } }
                g.optString("client_secret", "").let { if (it.isNotEmpty()) { TokenManager.save(TokenManager.KEY_G_CLIENT_SECRET, it); count++ } }
                g.optString("access_token", "").let { if (it.isNotEmpty()) { TokenManager.save(TokenManager.KEY_G_ACCESS, it); count++ } }
                g.optString("refresh_token", "").let { if (it.isNotEmpty()) { TokenManager.save(TokenManager.KEY_G_REFRESH, it); count++ } }
                if (g.has("expires_at")) { TokenManager.saveLong(TokenManager.KEY_G_EXPIRY, g.getLong("expires_at")); count++ }
            }

            if (json.has("microsoft")) {
                val ms = json.getJSONObject("microsoft")
                ms.optString("client_id", "").let { if (it.isNotEmpty()) { TokenManager.save(TokenManager.KEY_MS_CLIENT_ID, it); count++ } }
                ms.optString("access_token", "").let { if (it.isNotEmpty()) { TokenManager.save(TokenManager.KEY_MS_ACCESS, it); count++ } }
                ms.optString("refresh_token", "").let { if (it.isNotEmpty()) { TokenManager.save(TokenManager.KEY_MS_REFRESH, it); count++ } }
                if (ms.has("expires_at")) { TokenManager.saveLong(TokenManager.KEY_MS_EXPIRY, ms.getLong("expires_at")); count++ }
            }

            updateAuthUI()
            Toast.makeText(this, "가져오기 완료! (${count}개 항목 복원)", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "가져오기 실패: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun parseGoogleJson(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val jsonStr = inputStream?.bufferedReader()?.readText() ?: return
            val json = JSONObject(jsonStr)
            var clientId = ""
            var clientSecret = ""
            if (json.has("installed")) {
                val installed = json.getJSONObject("installed")
                clientId = installed.optString("client_id", "")
                clientSecret = installed.optString("client_secret", "")
            } else if (json.has("web")) {
                val web = json.getJSONObject("web")
                clientId = web.optString("client_id", "")
                clientSecret = web.optString("client_secret", "")
            } else {
                clientId = json.optString("client_id", "")
                clientSecret = json.optString("client_secret", "")
            }
            if (clientId.isNotEmpty()) {
                TokenManager.save(TokenManager.KEY_G_CLIENT_ID, clientId)
                if (clientSecret.isNotEmpty()) TokenManager.save(TokenManager.KEY_G_CLIENT_SECRET, clientSecret)
                updateAuthUI()
                Toast.makeText(this, "JSON에서 가져오기 완료", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Client ID를 찾을 수 없습니다", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "JSON 파싱 오류: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9999)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateAuthUI()
    }
}
