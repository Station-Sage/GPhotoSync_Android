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

class MainActivity : AppCompatActivity() {

    private lateinit var progressDb: SyncProgressStore
    private var isSyncing = false
    private val handler = Handler(Looper.getMainLooper())
    private var liveLogLines = mutableListOf<String>()

    // Tab views
    private var syncView: View? = null
    private var authView: View? = null
    private var infoView: View? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TokenManager.init(this)
        applySavedTheme()
        setContentView(R.layout.activity_main)
        progressDb = SyncProgressStore(this)

        setupTabs()

        SyncForegroundService.progressCallback = { progress ->
            runOnUiThread { updateProgress(progress) }
        }
        SyncForegroundService.logCallback = { line ->
            runOnUiThread { appendLiveLog(line) }
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

        syncView = LayoutInflater.from(this).inflate(R.layout.tab_sync, contentFrame, false)
        authView = LayoutInflater.from(this).inflate(R.layout.tab_auth, contentFrame, false)
        infoView = LayoutInflater.from(this).inflate(R.layout.tab_info, contentFrame, false)

        setupSyncTab()
        setupAuthTab()
        setupInfoTab()

        contentFrame.addView(syncView)

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                contentFrame.removeAllViews()
                when (tab.position) {
                    0 -> { contentFrame.addView(syncView); loadHistorySummary() }
                    1 -> { contentFrame.addView(authView); updateAuthUI() }
                    2 -> contentFrame.addView(infoView)
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
        val successRecords = progressDb.getSuccessRecords()
        val failedRecords = progressDb.getFailedRecords()
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
        val entry = "$now | 성공:$success 스킵:${progress.skipped} 실패:${progress.errors} (전체:${progress.total}, ${totalMB}MB)"
        val history = prefs.getStringSet("entries", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        history.add(entry)
        prefs.edit().putStringSet("entries", history).putString("latest", entry).apply()
    }

    private fun loadHistorySummary() {
        val v = syncView ?: return
        val prefs = getSharedPreferences("sync_history", MODE_PRIVATE)
        val history = prefs.getStringSet("entries", emptySet()) ?: emptySet()
        val card = v.findViewById<View>(R.id.cardHistory)
        if (history.isNotEmpty()) {
            card.visibility = View.VISIBLE
            val sorted = history.sortedDescending()
            v.findViewById<TextView>(R.id.tvHistorySummary).text = sorted.take(10).joinToString("\n")
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

        AlertDialog.Builder(this)
            .setTitle("Microsoft API 설정")
            .setView(layout)
            .setPositiveButton("저장") { _, _ ->
                val id = etId.text.toString().trim()
                if (id.isNotEmpty()) {
                    TokenManager.save(TokenManager.KEY_MS_CLIENT_ID, id)
                    updateAuthUI()
                    Toast.makeText(this, "저장 완료", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
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

    override fun onResume() {
        super.onResume()
        updateAuthUI()
    }
}
