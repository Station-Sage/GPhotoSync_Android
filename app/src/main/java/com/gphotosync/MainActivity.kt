package com.gphotosync

import android.app.Activity
import android.view.View
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.gphotosync.databinding.ActivityMainBinding
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var syncRunning = false

    private val googleAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            updateAuthStatus()
            Toast.makeText(this, "Google 인증 완료!", Toast.LENGTH_SHORT).show()
        }
    }

    private val msAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            updateAuthStatus()
            Toast.makeText(this, "Microsoft 인증 완료!", Toast.LENGTH_SHORT).show()
        }
    }

    // JSON 파일 선택기
    private val jsonFilePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { loadOAuthJsonFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        TokenManager.init(this)
        updateAuthStatus()

        binding.btnGoogleSetup.setOnClickListener { showGoogleSetupDialog() }

        binding.btnGoogleAuth.setOnClickListener {
            if (TokenManager.get(TokenManager.KEY_G_CLIENT_ID).isNullOrEmpty()) {
                Toast.makeText(this, "먼저 Google Client ID를 설정하세요", Toast.LENGTH_SHORT).show()
            } else {
                val intent = Intent(this, OAuthActivity::class.java).apply {
                    putExtra(OAuthActivity.EXTRA_TYPE, "google")
                }
                googleAuthLauncher.launch(intent)
            }
        }

        binding.btnMsSetup.setOnClickListener { showMsSetupDialog() }

        binding.btnMsAuth.setOnClickListener {
            if (TokenManager.get(TokenManager.KEY_MS_CLIENT_ID).isNullOrEmpty()) {
                Toast.makeText(this, "먼저 Microsoft Client ID를 설정하세요", Toast.LENGTH_SHORT).show()
            } else {
                val intent = Intent(this, OAuthActivity::class.java).apply {
                    putExtra(OAuthActivity.EXTRA_TYPE, "microsoft")
                }
                msAuthLauncher.launch(intent)
            }
        }

        binding.btnSync.setOnClickListener {
            if (!syncRunning) startSync() else stopSync()
        }

        binding.btnReset.setOnClickListener { showResetDialog() }

        SyncForegroundService.progressCallback = { progress ->
            runOnUiThread { updateProgress(progress) }
        }
    }

    override fun onResume() {
        super.onResume()
        updateAuthStatus()
    }

    private fun loadOAuthJsonFile(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val jsonStr = inputStream?.bufferedReader()?.readText() ?: ""
            inputStream?.close()

            val root = JSONObject(jsonStr)

            // Google Cloud Console에서 다운로드한 JSON 파싱
            // 형식 1: {"installed": {"client_id": "...", "client_secret": "..."}}
            // 형식 2: {"web": {"client_id": "...", "client_secret": "..."}}
            val clientObj = when {
                root.has("installed") -> root.getJSONObject("installed")
                root.has("web") -> root.getJSONObject("web")
                root.has("client_id") -> root  // 직접 포맷
                else -> null
            }

            if (clientObj != null && clientObj.has("client_id")) {
                val clientId = clientObj.getString("client_id")
                val clientSecret = clientObj.optString("client_secret", "")

                TokenManager.save(TokenManager.KEY_G_CLIENT_ID, clientId)
                if (clientSecret.isNotEmpty()) {
                    TokenManager.save(TokenManager.KEY_G_CLIENT_SECRET, clientSecret)
                }

                updateAuthStatus()
                Toast.makeText(this, "JSON에서 설정 완료!\nClient ID: ${clientId.take(30)}...", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "JSON 파일에서 client_id를 찾을 수 없습니다", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "JSON 파싱 오류: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateAuthStatus() {
        val gAuth  = TokenManager.isGoogleAuthed()
        val msAuth = TokenManager.isMicrosoftAuthed()

        binding.tvGoogleStatus.text  = if (gAuth) "Google 인증 완료" else "Google 미인증"
        binding.tvMsStatus.text      = if (msAuth) "Microsoft 인증 완료" else "Microsoft 미인증"

        binding.btnGoogleAuth.text   = if (gAuth) "Google 재인증" else "Google 로그인"
        binding.btnMsAuth.text       = if (msAuth) "Microsoft 재인증" else "Microsoft 로그인"

        binding.btnSync.isEnabled    = gAuth && msAuth
        binding.btnSync.alpha        = if (gAuth && msAuth) 1.0f else 0.5f

        val gId = TokenManager.get(TokenManager.KEY_G_CLIENT_ID)
        binding.tvGoogleClientId.text = if (!gId.isNullOrEmpty()) "Client ID: ${gId.take(20)}..." else "Client ID 미설정"

        val msId = TokenManager.get(TokenManager.KEY_MS_CLIENT_ID)
        binding.tvMsClientId.text = if (!msId.isNullOrEmpty()) "Client ID: ${msId.take(20)}..." else "Client ID 미설정"
    }

    private fun updateProgress(progress: SyncProgress) {
        if (progress.errorMessage != null) {
            binding.tvStatus.text = "오류: ${progress.errorMessage}"
            syncRunning = false
            binding.btnSync.text = "동기화 시작"
            binding.progressBar.visibility = View.GONE
            return
        }

        val pct = if (progress.total > 0) (progress.done * 100 / progress.total) else 0
        binding.progressBar.progress = pct
        binding.progressBar.max = 100
        binding.tvStatus.text = "진행: ${progress.done}/${progress.total} (${pct}%)\n오류: ${progress.errors}개"

        if (progress.finished) {
            syncRunning = false
            binding.btnSync.text = "동기화 시작"
            binding.tvStatus.text = "완료! ${progress.done}개 성공, ${progress.errors}개 오류"
            binding.progressBar.visibility = View.GONE
        }
    }

    private fun startSync() {
        syncRunning = true
        binding.btnSync.text = "동기화 중단"
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true
        binding.tvStatus.text = "동기화 준비 중..."

        val intent = Intent(this, SyncForegroundService::class.java).apply {
            action = SyncForegroundService.ACTION_START
        }
        startForegroundService(intent)
    }

    private fun stopSync() {
        syncRunning = false
        binding.btnSync.text = "동기화 시작"
        binding.tvStatus.text = "동기화 중단됨"
        binding.progressBar.visibility = View.GONE

        val intent = Intent(this, SyncForegroundService::class.java).apply {
            action = SyncForegroundService.ACTION_STOP
        }
        startService(intent)
    }

    private fun showGoogleSetupDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "Client ID (xxxxx.apps.googleusercontent.com)"
            setText(TokenManager.get(TokenManager.KEY_G_CLIENT_ID) ?: "")
        }
        val secretInput = android.widget.EditText(this).apply {
            hint = "Client Secret (GOCSPX-xxxxx)"
            setText(TokenManager.get(TokenManager.KEY_G_CLIENT_SECRET) ?: "")
        }
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 20, 50, 10)
            addView(android.widget.TextView(this@MainActivity).apply {
                text = "방법 1: JSON 파일로 자동 설정\n(Google Cloud Console에서 다운로드한 OAuth JSON)\n\n방법 2: 수동 입력"
                setPadding(0, 0, 0, 20)
                textSize = 13f
            })

            // JSON 업로드 버튼
            addView(android.widget.Button(this@MainActivity).apply {
                text = "JSON 파일 업로드"
                setOnClickListener {
                    jsonFilePicker.launch("application/json")
                }
            })

            addView(android.view.View(this@MainActivity).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply { topMargin = 16; bottomMargin = 16 }
                setBackgroundColor(0xFFCCCCCC.toInt())
            })

            addView(android.widget.TextView(this@MainActivity).apply {
                text = "또는 수동 입력:"
                setPadding(0, 0, 0, 8)
            })
            addView(android.widget.TextView(this@MainActivity).apply { text = "Client ID:" })
            addView(input)
            addView(android.widget.TextView(this@MainActivity).apply {
                text = "Client Secret:"
                setPadding(0, 16, 0, 0)
            })
            addView(secretInput)
        }

        AlertDialog.Builder(this)
            .setTitle("Google 설정")
            .setView(layout)
            .setPositiveButton("저장") { _, _ ->
                val id  = input.text.toString().trim()
                val sec = secretInput.text.toString().trim()
                if (id.isNotEmpty()) {
                    TokenManager.save(TokenManager.KEY_G_CLIENT_ID, id)
                    TokenManager.save(TokenManager.KEY_G_CLIENT_SECRET, sec)
                    updateAuthStatus()
                    Toast.makeText(this, "저장됨", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showMsSetupDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "Application (Client) ID"
            setText(TokenManager.get(TokenManager.KEY_MS_CLIENT_ID) ?: "")
        }
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 20, 50, 10)
            addView(android.widget.TextView(this@MainActivity).apply {
                text = "Azure Portal에서 앱 등록 후\nApplication (Client) ID를 입력하세요"
                setPadding(0, 0, 0, 20)
                textSize = 13f
            })
            addView(android.widget.TextView(this@MainActivity).apply { text = "Application (Client) ID:" })
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Microsoft 설정")
            .setView(layout)
            .setPositiveButton("저장") { _, _ ->
                val id = input.text.toString().trim()
                if (id.isNotEmpty()) {
                    TokenManager.save(TokenManager.KEY_MS_CLIENT_ID, id)
                    updateAuthStatus()
                    Toast.makeText(this, "저장됨", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showResetDialog() {
        AlertDialog.Builder(this)
            .setTitle("초기화")
            .setMessage("저장된 모든 인증 정보와 동기화 진행 상황을 삭제할까요?")
            .setPositiveButton("초기화") { _, _ ->
                TokenManager.clearAll()
                SyncProgressStore(this).reset()
                updateAuthStatus()
                binding.tvStatus.text = "초기화 완료"
                Toast.makeText(this, "초기화 완료", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        SyncForegroundService.progressCallback = null
    }
}
