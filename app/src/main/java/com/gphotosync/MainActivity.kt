package com.gphotosync

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.tabs.TabLayout
import org.json.JSONObject
import java.io.File
import java.io.FileWriter

class MainActivity : AppCompatActivity() {

    private lateinit var progressDb: SyncProgressStore
    private var syncView: View? = null
    private var authView: View? = null
    private var infoView: View? = null
    private var takeoutView: View? = null

    private lateinit var syncHelper: SyncTabHelper
    private lateinit var takeoutHelper: TakeoutTabHelper

    private val googleAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) Toast.makeText(this, "Google 인증 성공!", Toast.LENGTH_SHORT).show()
        updateAuthUI()
    }

    private val msAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) Toast.makeText(this, "Microsoft 인증 성공!", Toast.LENGTH_SHORT).show()
        updateAuthUI()
    }

    private val jsonFilePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? -> uri?.let { parseGoogleJson(it) } }
    private val authJsonPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? -> uri?.let { importAuthFromJson(it) } }
    private val msJsonPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? -> uri?.let { parseMsJson(it) } }
    private val exportAuthPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri?.let { writeAuthJson(it) }
    }
    private val logExportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        uri?.let { exportLogToUri(it) }
    }
    private val googleConfigExportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri?.let { writeGoogleConfigJson(it) }
    }
    private val msConfigExportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri?.let { writeMsConfigJson(it) }
    }
    private val zipFilePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? -> uri?.let { takeoutHelper.onZipSelected(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TokenManager.init(this)
        applySavedTheme()
        setContentView(R.layout.activity_main)
        progressDb = SyncProgressStore(this)
        setupTabs()
        requestNotificationPermission()
        intent?.let { handleOpenTab(it) }

        SyncForegroundService.progressCallback = { p -> runOnUiThread { syncHelper.updateProgress(p) } }
        SyncForegroundService.logCallback = { l -> runOnUiThread { syncHelper.appendLiveLog(l) } }
        takeoutHelper.setupCallbacks()
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

        syncHelper = SyncTabHelper(this, syncView!!, progressDb)
        takeoutHelper = TakeoutTabHelper(this, takeoutView!!, zipFilePicker)

        syncHelper.setup()
        setupAuthTab()
        setupInfoTab()
        takeoutHelper.setup()

        contentFrame.addView(syncView)

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                getSharedPreferences("app_settings", MODE_PRIVATE).edit().putInt("last_tab", tab.position).apply()
                contentFrame.removeAllViews()
                when (tab.position) {
                    0 -> { contentFrame.addView(syncView); syncHelper.loadHistorySummary() }
                    1 -> { contentFrame.addView(authView); updateAuthUI() }
                    2 -> contentFrame.addView(infoView)
                    3 -> { contentFrame.addView(takeoutView); takeoutHelper.setupCallbacks(); takeoutHelper.restoreState() }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    // ======== AUTH TAB ========
    private fun setupAuthTab() {
        val v = authView ?: return
        v.findViewById<Button>(R.id.btnGoogleSetup).setOnClickListener { showGoogleSetupDialog() }
        v.findViewById<Button>(R.id.btnGoogleAuth).setOnClickListener {
            if (TokenManager.get(TokenManager.KEY_G_CLIENT_ID).isNullOrEmpty()) { Toast.makeText(this, "먼저 API 설정을 하세요", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val intent = Intent(this, OAuthActivity::class.java); intent.putExtra(OAuthActivity.EXTRA_TYPE, "google"); googleAuthLauncher.launch(intent)
        }
        v.findViewById<Button>(R.id.btnMsSetup).setOnClickListener { showMsSetupDialog() }
        v.findViewById<Button>(R.id.btnMsAuth).setOnClickListener {
            if (TokenManager.get(TokenManager.KEY_MS_CLIENT_ID).isNullOrEmpty()) { Toast.makeText(this, "먼저 API 설정을 하세요", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val intent = Intent(this, OAuthActivity::class.java); intent.putExtra(OAuthActivity.EXTRA_TYPE, "microsoft"); msAuthLauncher.launch(intent)
        }
        v.findViewById<Button>(R.id.btnExportAuth).setOnClickListener { exportAuthToJson() }
        v.findViewById<Button>(R.id.btnImportAuth).setOnClickListener { authJsonPicker.launch("*/*") }
        v.findViewById<Button>(R.id.btnReset).setOnClickListener {
            AlertDialog.Builder(this).setTitle("초기화").setMessage("모든 인증 정보와 동기화 기록을 삭제합니다.")
                .setPositiveButton("확인") { _, _ ->
                    TokenManager.clearAll(); progressDb.reset()
                    getSharedPreferences("sync_history", MODE_PRIVATE).edit().clear().apply()
                    updateAuthUI(); Toast.makeText(this, "초기화 완료", Toast.LENGTH_SHORT).show()
                }.setNegativeButton("취소", null).show()
        }
        updateAuthUI()
    }

    private fun updateAuthUI() {
        val v = authView ?: return
        val gId = TokenManager.get(TokenManager.KEY_G_CLIENT_ID)
        val gToken = TokenManager.get(TokenManager.KEY_G_ACCESS)
        v.findViewById<TextView>(R.id.tvGoogleClientId).text = if (!gId.isNullOrEmpty()) "Client ID: ${gId.take(20)}..." else "Client ID 미설정"
        v.findViewById<TextView>(R.id.tvGoogleStatus).text = if (TokenManager.isGoogleAuthed()) "✅ 인증 완료" else "❌ 미인증"
        v.findViewById<Button>(R.id.btnGoogleAuth).text = if (!gToken.isNullOrEmpty()) "재인증" else "로그인"
        val msId = TokenManager.get(TokenManager.KEY_MS_CLIENT_ID)
        val msToken = TokenManager.get(TokenManager.KEY_MS_ACCESS)
        v.findViewById<TextView>(R.id.tvMsClientId).text = if (!msId.isNullOrEmpty()) "Client ID: ${msId.take(20)}..." else "Client ID 미설정"
        v.findViewById<TextView>(R.id.tvMsStatus).text = if (TokenManager.isMicrosoftAuthed()) "✅ 인증 완료" else "❌ 미인증"
        v.findViewById<Button>(R.id.btnMsAuth).text = if (!msToken.isNullOrEmpty()) "재인증" else "로그인"
    }

    // ======== INFO TAB ========
    private fun setupInfoTab() {
        val v = infoView ?: return
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        val rg = v.findViewById<RadioGroup>(R.id.rgTheme)
        when (prefs.getInt("theme", 0)) {
            0 -> v.findViewById<RadioButton>(R.id.rbSystem).isChecked = true
            1 -> v.findViewById<RadioButton>(R.id.rbLight).isChecked = true
            2 -> v.findViewById<RadioButton>(R.id.rbDark).isChecked = true
        }
        rg.setOnCheckedChangeListener { _, checkedId ->
            val theme = when (checkedId) {
                R.id.rbLight -> 1
                R.id.rbDark -> 2
                else -> 0
            }
            prefs.edit().putInt("theme", theme).apply()
            applySavedTheme()
        }
        v.findViewById<Button>(R.id.btnExportLog)?.setOnClickListener {
            val logFile = java.io.File(filesDir, "sync_log.txt")
            if (logFile.exists() && logFile.length() > 0) {
                logExportPicker.launch("sync_log.txt")
            } else {
                Toast.makeText(this, "로그 파일이 없습니다", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun exportLogToUri(uri: Uri) {
        try {
            val logFile = java.io.File(filesDir, "sync_log.txt")
            contentResolver.openOutputStream(uri)?.use { out ->
                logFile.inputStream().use { it.copyTo(out) }
            }
            Toast.makeText(this, "로그 내보내기 완료", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "로그 내보내기 실패: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ======== DIALOGS ========
    private fun showGoogleSetupDialog() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 32, 48, 16) }
        val etClientId = EditText(this).apply { hint = "Client ID"; setText(TokenManager.get(TokenManager.KEY_G_CLIENT_ID) ?: "") }
        val etSecret = EditText(this).apply { hint = "Client Secret"; setText(TokenManager.get(TokenManager.KEY_G_CLIENT_SECRET) ?: "") }
        layout.addView(etClientId); layout.addView(etSecret)
        val rg = RadioGroup(this)
        val rbManual = RadioButton(this).apply { text = "직접 입력"; isChecked = true }
        val rbJson = RadioButton(this).apply { text = "JSON 파일에서 가져오기"; setOnClickListener { rbManual.isChecked = true; jsonFilePicker.launch("*/*") } }
        rg.addView(rbManual); rg.addView(rbJson); layout.addView(rg)
        AlertDialog.Builder(this).setTitle("Google API 설정").setView(layout)
            .setPositiveButton("저장") { _, _ ->
                if (rbJson.isChecked) { jsonFilePicker.launch("*/*") } else {
                    val id = etClientId.text.toString().trim(); val sec = etSecret.text.toString().trim()
                    if (id.isNotEmpty()) {
                        TokenManager.save(TokenManager.KEY_G_CLIENT_ID, id)
                        if (sec.isNotEmpty()) TokenManager.save(TokenManager.KEY_G_CLIENT_SECRET, sec)
                        updateAuthUI(); Toast.makeText(this, "Google API 설정 완료", Toast.LENGTH_SHORT).show()
                    } else { Toast.makeText(this, "Client ID를 입력하세요", Toast.LENGTH_SHORT).show() }
                }
            }.setNeutralButton("JSON 내보내기") { _, _ ->
                googleConfigExportPicker.launch("google_api_config.json")
            }.setNegativeButton("취소", null).show()
    }

    private fun writeGoogleConfigJson(uri: Uri) {
        try {
            val json = JSONObject()
            json.put("client_id", TokenManager.get(TokenManager.KEY_G_CLIENT_ID) ?: "")
            json.put("client_secret", TokenManager.get(TokenManager.KEY_G_CLIENT_SECRET) ?: "")
            contentResolver.openOutputStream(uri)?.use { it.write(json.toString(2).toByteArray()) }
            Toast.makeText(this, "내보내기 완료", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(this, "내보내기 실패: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun showMsSetupDialog() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 32, 48, 16) }
        val etClientId = EditText(this).apply { hint = "Application (client) ID"; setText(TokenManager.get(TokenManager.KEY_MS_CLIENT_ID) ?: "") }
        val etSecret = EditText(this).apply { hint = "Client Secret (선택)"; setText(TokenManager.get("ms_client_secret") ?: "") }
        layout.addView(etClientId); layout.addView(etSecret)
        val rg = RadioGroup(this)
        val rbManual = RadioButton(this).apply { text = "직접 입력"; isChecked = true }
        val rbJson = RadioButton(this).apply { text = "JSON 파일에서 가져오기"; setOnClickListener { rbManual.isChecked = true; msJsonPicker.launch("*/*") } }
        rg.addView(rbManual); rg.addView(rbJson); layout.addView(rg)
        layout.addView(TextView(this).apply {
            text = "Azure Portal → 앱 등록에서:\n• 리디렉션 URI: http://localhost\n• API 권한: Files.ReadWrite, offline_access"
            setPadding(0, 16, 0, 0); textSize = 12f
        })
        AlertDialog.Builder(this).setTitle("Microsoft API 설정").setView(layout)
            .setPositiveButton("저장") { _, _ ->
                if (rbJson.isChecked) { msJsonPicker.launch("*/*") } else {
                    val id = etClientId.text.toString().trim()
                    if (id.isNotEmpty()) {
                        TokenManager.save(TokenManager.KEY_MS_CLIENT_ID, id)
                        val sec = etSecret.text.toString().trim()
                        if (sec.isNotEmpty()) TokenManager.save("ms_client_secret", sec)
                        updateAuthUI(); Toast.makeText(this, "Microsoft API 설정 완료", Toast.LENGTH_SHORT).show()
                    } else { Toast.makeText(this, "Client ID를 입력하세요", Toast.LENGTH_SHORT).show() }
                }
            }.setNeutralButton("JSON 내보내기") { _, _ ->
                msConfigExportPicker.launch("ms_api_config.json")
            }.setNegativeButton("취소", null).show()
    }

    private fun writeMsConfigJson(uri: Uri) {
        try {
            val json = JSONObject()
            json.put("client_id", TokenManager.get(TokenManager.KEY_MS_CLIENT_ID) ?: "")
            json.put("client_secret", TokenManager.get("ms_client_secret") ?: "")
            contentResolver.openOutputStream(uri)?.use { it.write(json.toString(2).toByteArray()) }
            Toast.makeText(this, "내보내기 완료", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(this, "내보내기 실패: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun exportAuthToJson() {
        exportAuthPicker.launch("gphotosync_auth.json")
    }

    private fun writeAuthJson(uri: Uri) {
        try {
            val json = JSONObject()
            json.put("g_client_id", TokenManager.get(TokenManager.KEY_G_CLIENT_ID) ?: "")
            json.put("g_client_secret", TokenManager.get(TokenManager.KEY_G_CLIENT_SECRET) ?: "")
            json.put("g_access_token", TokenManager.get(TokenManager.KEY_G_ACCESS) ?: "")
            json.put("g_refresh_token", TokenManager.get(TokenManager.KEY_G_REFRESH) ?: "")
            json.put("g_expires_at", TokenManager.getLong(TokenManager.KEY_G_EXPIRY))
            json.put("ms_client_id", TokenManager.get(TokenManager.KEY_MS_CLIENT_ID) ?: "")
            json.put("ms_client_secret", TokenManager.get("ms_client_secret") ?: "")
            json.put("ms_access_token", TokenManager.get(TokenManager.KEY_MS_ACCESS) ?: "")
            json.put("ms_refresh_token", TokenManager.get(TokenManager.KEY_MS_REFRESH) ?: "")
            json.put("ms_expires_at", TokenManager.getLong(TokenManager.KEY_MS_EXPIRY))
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json.toString(2)) }
            Toast.makeText(this, "내보내기 완료", Toast.LENGTH_LONG).show()
        } catch (e: Exception) { Toast.makeText(this, "내보내기 실패: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun importAuthFromJson(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return
            val json = JSONObject(text)
            if (json.has("g_client_id")) TokenManager.save(TokenManager.KEY_G_CLIENT_ID, json.getString("g_client_id"))
            if (json.has("g_client_secret")) TokenManager.save(TokenManager.KEY_G_CLIENT_SECRET, json.getString("g_client_secret"))
            if (json.has("g_access_token")) TokenManager.save(TokenManager.KEY_G_ACCESS, json.getString("g_access_token"))
            if (json.has("g_refresh_token")) TokenManager.save(TokenManager.KEY_G_REFRESH, json.getString("g_refresh_token"))
            if (json.has("g_expires_at")) TokenManager.saveLong(TokenManager.KEY_G_EXPIRY, json.getLong("g_expires_at"))
            if (json.has("ms_client_id")) TokenManager.save(TokenManager.KEY_MS_CLIENT_ID, json.getString("ms_client_id"))
            if (json.has("ms_client_secret")) TokenManager.save(TokenManager.KEY_MS_CLIENT_SECRET, json.getString("ms_client_secret"))
            if (json.has("ms_access_token")) TokenManager.save(TokenManager.KEY_MS_ACCESS, json.getString("ms_access_token"))
            if (json.has("ms_refresh_token")) TokenManager.save(TokenManager.KEY_MS_REFRESH, json.getString("ms_refresh_token"))
            if (json.has("ms_expires_at")) TokenManager.saveLong(TokenManager.KEY_MS_EXPIRY, json.getLong("ms_expires_at"))
            updateAuthUI(); Toast.makeText(this, "가져오기 완료!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(this, "가져오기 실패: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun parseMsJson(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return
            val json = JSONObject(text)
            val clientId = json.optString("client_id", json.optString("appId", ""))
            val clientSecret = json.optString("client_secret", json.optString("password", ""))
            if (clientId.isNotEmpty()) {
                TokenManager.save(TokenManager.KEY_MS_CLIENT_ID, clientId)
                if (clientSecret.isNotEmpty()) TokenManager.save("ms_client_secret", clientSecret)
                updateAuthUI(); Toast.makeText(this, "MS JSON에서 가져오기 완료", Toast.LENGTH_SHORT).show()
            } else { Toast.makeText(this, "Client ID를 찾을 수 없습니다", Toast.LENGTH_LONG).show() }
        } catch (e: Exception) { Toast.makeText(this, "JSON 파싱 오류: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun parseGoogleJson(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return
            val json = JSONObject(text)
            val installed = json.optJSONObject("installed") ?: json.optJSONObject("web") ?: json
            val clientId = installed.optString("client_id", "")
            val clientSecret = installed.optString("client_secret", "")
            if (clientId.isNotEmpty()) {
                TokenManager.save(TokenManager.KEY_G_CLIENT_ID, clientId)
                if (clientSecret.isNotEmpty()) TokenManager.save(TokenManager.KEY_G_CLIENT_SECRET, clientSecret)
                updateAuthUI(); Toast.makeText(this, "JSON에서 가져오기 완료", Toast.LENGTH_SHORT).show()
            } else { Toast.makeText(this, "Client ID를 찾을 수 없습니다", Toast.LENGTH_LONG).show() }
        } catch (e: Exception) { Toast.makeText(this, "JSON 파싱 오류: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    // ======== PERMISSIONS & LIFECYCLE ========
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
                    AlertDialog.Builder(this).setTitle("알림 권한 필요").setMessage("동기화 진행 상태를 알림으로 표시하려면 알림 권한이 필요합니다.")
                        .setPositiveButton("권한 허용") { _, _ -> ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9999) }
                        .setNegativeButton("취소", null).show()
                } else { ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9999) }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 9999) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) Toast.makeText(this, "알림 권한이 허용되었습니다", Toast.LENGTH_SHORT).show()
            else Toast.makeText(this, "알림 권한이 거부되었습니다. 설정에서 직접 허용해주세요.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) { setIntent(intent); handleOpenTab(intent) }
    }

    private fun handleOpenTab(intent: Intent) {
        val tabIndex = intent.getIntExtra("OPEN_TAB", -1)
        if (tabIndex >= 0) {
            intent.removeExtra("OPEN_TAB")
            val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
            if (tabIndex < tabLayout.tabCount) {
                if (tabLayout.selectedTabPosition == tabIndex && tabIndex == 3) {
                    takeoutHelper.setupCallbacks(); takeoutHelper.restoreState()
                } else { tabLayout.getTabAt(tabIndex)?.select() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateAuthUI()
        takeoutHelper.setupCallbacks()
        SyncForegroundService.progressCallback = { p -> runOnUiThread { syncHelper.updateProgress(p) } }
        SyncForegroundService.logCallback = { l -> runOnUiThread { syncHelper.appendLiveLog(l) } }
        // 백그라운드에서 완료/중단된 경우 isSyncing 플래그 복원
        if (SyncForegroundService.isRunning) {
            syncHelper.setSyncingUI()
        } else if (syncHelper.isSyncing) {
            syncHelper.setIdleUI()
        }
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        val savedTab = getSharedPreferences("app_settings", MODE_PRIVATE).getInt("last_tab", 0)
        if (tabLayout.selectedTabPosition != savedTab) tabLayout.getTabAt(savedTab)?.select()
        if (tabLayout.selectedTabPosition == 3) takeoutHelper.restoreState()
    }
}
