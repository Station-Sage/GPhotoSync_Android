package com.gphotosync

import android.app.Activity
import android.view.View
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.gphotosync.databinding.ActivityMainBinding
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var progressDb: SyncProgressStore
    private var isSyncing = false

    private val googleAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(this, "Google 인증 성공!", Toast.LENGTH_SHORT).show()
        }
        updateUI()
    }

    private val msAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(this, "Microsoft 인증 성공!", Toast.LENGTH_SHORT).show()
        }
        updateUI()
    }

    private val jsonFilePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { parseGoogleJson(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TokenManager.init(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        progressDb = SyncProgressStore(this)

        setupButtons()
        updateUI()
        setupDetailTabs()

        SyncForegroundService.progressCallback = { progress ->
            runOnUiThread { updateProgress(progress) }
        }
    }

    private fun setupButtons() {
        binding.btnGoogleSetup.setOnClickListener { showGoogleSetupDialog() }
        binding.btnGoogleAuth.setOnClickListener {
            if (TokenManager.get(TokenManager.KEY_G_CLIENT_ID).isNullOrEmpty()) {
                Toast.makeText(this, "먼저 API 설정을 하세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(this, OAuthActivity::class.java)
            intent.putExtra(OAuthActivity.EXTRA_TYPE, "google")
            googleAuthLauncher.launch(intent)
        }

        binding.btnMsSetup.setOnClickListener { showMsSetupDialog() }
        binding.btnMsAuth.setOnClickListener {
            if (TokenManager.get(TokenManager.KEY_MS_CLIENT_ID).isNullOrEmpty()) {
                Toast.makeText(this, "먼저 API 설정을 하세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(this, OAuthActivity::class.java)
            intent.putExtra(OAuthActivity.EXTRA_TYPE, "microsoft")
            msAuthLauncher.launch(intent)
        }

        binding.btnSync.setOnClickListener {
            if (isSyncing) {
                val stopIntent = Intent(this, SyncForegroundService::class.java)
                stopIntent.action = SyncForegroundService.ACTION_STOP
                startForegroundService(stopIntent)
                isSyncing = false
                binding.btnSync.text = "동기화 시작"
                binding.btnSync.setBackgroundColor(0xFF2E7D32.toInt())
            } else {
                startSync()
            }
        }

        binding.btnReset.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("초기화")
                .setMessage("모든 인증 정보와 동기화 기록을 삭제합니다.")
                .setPositiveButton("확인") { _, _ ->
                    TokenManager.clearAll()
                    progressDb.reset()
                    updateUI()
                    Toast.makeText(this, "초기화 완료", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("취소", null)
                .show()
        }

        binding.btnRetryFailed.setOnClickListener { retryFailed() }
    }

    private fun setupDetailTabs() {
        binding.btnTabSuccess.setOnClickListener {
            binding.layoutSuccess.visibility = View.VISIBLE
            binding.layoutFailed.visibility = View.GONE
            binding.btnTabSuccess.setBackgroundColor(0xFF4CAF50.toInt())
            binding.btnTabFailed.setBackgroundColor(0xFF9E9E9E.toInt())
            refreshDetailLists()
        }

        binding.btnTabFailed.setOnClickListener {
            binding.layoutSuccess.visibility = View.GONE
            binding.layoutFailed.visibility = View.VISIBLE
            binding.btnTabSuccess.setBackgroundColor(0xFF9E9E9E.toInt())
            binding.btnTabFailed.setBackgroundColor(0xFFF44336.toInt())
            refreshDetailLists()
        }
    }

    private fun refreshDetailLists() {
        val successRecords = progressDb.getSuccessRecords()
        val failedRecords = progressDb.getFailedRecords()
        val dateFormat = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())

        binding.tvSuccessCount.text = "완료: ${successRecords.size}건"
        if (successRecords.isEmpty()) {
            binding.tvSuccessList.text = "내역 없음"
        } else {
            binding.tvSuccessList.text = successRecords.take(100).joinToString("\n") { r ->
                val time = dateFormat.format(Date(r.timestamp))
                val size = if (r.fileSize > 0) formatSize(r.fileSize) else ""
                "[$time] ${r.filename} $size"
            }
        }

        binding.tvFailedCount.text = "실패: ${failedRecords.size}건"
        if (failedRecords.isEmpty()) {
            binding.tvFailedList.text = "내역 없음"
        } else {
            binding.tvFailedList.text = failedRecords.joinToString("\n") { r ->
                val time = dateFormat.format(Date(r.timestamp))
                "[$time] ${r.filename}\n  → ${r.error}"
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format("%.1fMB", bytes / 1024.0 / 1024.0)
            bytes >= 1024 -> String.format("%.0fKB", bytes / 1024.0)
            else -> "${bytes}B"
        }
    }

    private fun startSync() {
        try { java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "sync_log.txt").appendText("${java.util.Date()} startSync called\n") } catch (_: Exception) {}
        isSyncing = true
        binding.btnSync.text = "동기화 중단"
        binding.btnSync.setBackgroundColor(0xFFF44336.toInt())
        binding.progressBar.visibility = View.VISIBLE
        binding.tvProgressPercent.visibility = View.VISIBLE
        binding.layoutStats.visibility = View.VISIBLE
        binding.cardDetails.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true

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
        binding.btnSync.text = "동기화 중단"
        binding.btnSync.setBackgroundColor(0xFFF44336.toInt())
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true

        SyncForegroundService.retryItems = failedRecords
        val intent = Intent(this, SyncForegroundService::class.java)
        intent.action = SyncForegroundService.ACTION_RETRY
        startForegroundService(intent)
    }

    private fun updateProgress(progress: SyncProgress) {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvProgressPercent.visibility = View.VISIBLE
        binding.layoutStats.visibility = View.VISIBLE
        binding.cardDetails.visibility = View.VISIBLE

        if (progress.total > 0) {
            binding.progressBar.isIndeterminate = false
            binding.progressBar.max = progress.total
            binding.progressBar.progress = progress.done
            val pct = progress.done * 100 / progress.total
            binding.tvProgressPercent.text = "${progress.done}/${progress.total} ($pct%)"
        }

        binding.tvTotal.text = "전체: ${progress.total}"
        binding.tvDone.text = "완료: ${progress.done - progress.errors - progress.skipped}"
        binding.tvSkipped.text = "스킵: ${progress.skipped}"
        binding.tvErrors.text = "실패: ${progress.errors}"

        if (progress.finished) {
            isSyncing = false
            binding.btnSync.text = "동기화 시작"
            binding.btnSync.setBackgroundColor(0xFF2E7D32.toInt())
            if (progress.errorMessage != null) {
                binding.tvStatus.text = "오류: ${progress.errorMessage}"
            } else {
                binding.tvStatus.text = "동기화 완료!"
            }
            refreshDetailLists()
        } else {
            binding.tvStatus.text = "동기화 중..."
        }
    }

    private fun updateUI() {
        val gId = TokenManager.get(TokenManager.KEY_G_CLIENT_ID)
        val gToken = TokenManager.get(TokenManager.KEY_G_ACCESS)
        binding.tvGoogleClientId.text = if (!gId.isNullOrEmpty()) "Client ID: ${gId.take(20)}..." else "Client ID 미설정"
        binding.tvGoogleStatus.text = if (!gToken.isNullOrEmpty()) "✅ Google 인증 완료" else "❌ Google 미인증"
        binding.btnGoogleAuth.text = if (!gToken.isNullOrEmpty()) "Google 재인증" else "Google 로그인"

        val msId = TokenManager.get(TokenManager.KEY_MS_CLIENT_ID)
        val msToken = TokenManager.get(TokenManager.KEY_MS_ACCESS)
        binding.tvMsClientId.text = if (!msId.isNullOrEmpty()) "Client ID: ${msId.take(20)}..." else "Client ID 미설정"
        binding.tvMsStatus.text = if (!msToken.isNullOrEmpty()) "✅ Microsoft 인증 완료" else "❌ Microsoft 미인증"
        binding.btnMsAuth.text = if (!msToken.isNullOrEmpty()) "Microsoft 재인증" else "Microsoft 로그인"

        val bothAuthed = !gToken.isNullOrEmpty() && !msToken.isNullOrEmpty()
        binding.btnSync.isEnabled = bothAuthed

        val successCount = progressDb.getSuccessRecords().size
        val failedCount = progressDb.getFailedRecords().size
        if (successCount > 0 || failedCount > 0) {
            binding.cardDetails.visibility = View.VISIBLE
            refreshDetailLists()
        }
    }

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
        layout.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                .apply { topMargin = 16; bottomMargin = 16 }
            setBackgroundColor(0xFFCCCCCC.toInt())
        })
        layout.addView(etSecret)
        layout.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                .apply { topMargin = 16; bottomMargin = 16 }
            setBackgroundColor(0xFFCCCCCC.toInt())
        })
        layout.addView(android.widget.Button(this).apply {
            text = "📁 JSON 파일에서 가져오기"
            setOnClickListener {
                jsonFilePicker.launch("*/*")
            }
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
                    updateUI()
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
                    updateUI()
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
                updateUI()
                Toast.makeText(this, "JSON에서 가져오기 완료", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "JSON에서 Client ID를 찾을 수 없습니다", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "JSON 파싱 오류: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }
}
