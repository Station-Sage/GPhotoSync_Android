package com.gphotosync

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.gphotosync.databinding.ActivityMainBinding

/**
 * 메인 화면
 * - Google / Microsoft 인증 상태 표시
 * - API 키 설정
 * - 동기화 시작/중단
 * - 실시간 진행 표시
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var syncRunning = false

    private val googleAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            updateAuthStatus()
            Toast.makeText(this, "✅ Google 인증 완료!", Toast.LENGTH_SHORT).show()
        }
    }

    private val msAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            updateAuthStatus()
            Toast.makeText(this, "✅ Microsoft 인증 완료!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        TokenManager.init(this)
        updateAuthStatus()

        // ── 버튼 이벤트 ──────────────────────────────

        // Google 설정 버튼
        binding.btnGoogleSetup.setOnClickListener { showGoogleSetupDialog() }

        // Google 인증 버튼
        binding.btnGoogleAuth.setOnClickListener {
            if (TokenManager.get(TokenManager.KEY_G_CLIENT_ID).isNullOrEmpty()) {
                Toast.makeText(this, "먼저 Google Client ID를 입력하세요", Toast.LENGTH_SHORT).show()
            } else {
                val intent = Intent(this, OAuthActivity::class.java).apply {
                    putExtra(OAuthActivity.EXTRA_TYPE, "google")
                }
                googleAuthLauncher.launch(intent)
            }
        }

        // Microsoft 설정 버튼
        binding.btnMsSetup.setOnClickListener { showMsSetupDialog() }

        // Microsoft 인증 버튼
        binding.btnMsAuth.setOnClickListener {
            if (TokenManager.get(TokenManager.KEY_MS_CLIENT_ID).isNullOrEmpty()) {
                Toast.makeText(this, "먼저 Microsoft Client ID를 입력하세요", Toast.LENGTH_SHORT).show()
            } else {
                val intent = Intent(this, OAuthActivity::class.java).apply {
                    putExtra(OAuthActivity.EXTRA_TYPE, "microsoft")
                }
                msAuthLauncher.launch(intent)
            }
        }

        // 동기화 시작/중단 버튼
        binding.btnSync.setOnClickListener {
            if (!syncRunning) startSync() else stopSync()
        }

        // 초기화 버튼
        binding.btnReset.setOnClickListener { showResetDialog() }

        // 실시간 진행 콜백 등록
        SyncForegroundService.progressCallback = { progress ->
            runOnUiThread { updateProgress(progress) }
        }
    }

    override fun onResume() {
        super.onResume()
        updateAuthStatus()
    }

    private fun updateAuthStatus() {
        val gAuth  = TokenManager.isGoogleAuthed()
        val msAuth = TokenManager.isMicrosoftAuthed()

        binding.tvGoogleStatus.text  = if (gAuth) "✅ Google 인증 완료" else "❌ Google 미인증"
        binding.tvMsStatus.text      = if (msAuth) "✅ Microsoft 인증 완료" else "❌ Microsoft 미인증"

        binding.btnGoogleAuth.text   = if (gAuth) "Google 재인증" else "Google 로그인"
        binding.btnMsAuth.text       = if (msAuth) "Microsoft 재인증" else "Microsoft 로그인"

        binding.btnSync.isEnabled    = gAuth && msAuth
        binding.btnSync.alpha        = if (gAuth && msAuth) 1.0f else 0.5f

        // Google 클라이언트 ID 힌트
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
        binding.tvStatus.text = "진행: ${progress.done}/${progress.total} ($pct%)\n오류: ${progress.errors}개"

        if (progress.finished) {
            syncRunning = false
            binding.btnSync.text = "동기화 시작"
            binding.tvStatus.text = "✅ 완료! ${progress.done}개 성공, ${progress.errors}개 오류"
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
        binding.tvStatus.text = "동기화 중단됨 (진행 상황 저장됨)"
        binding.progressBar.visibility = View.GONE

        val intent = Intent(this, SyncForegroundService::class.java).apply {
            action = SyncForegroundService.ACTION_STOP
        }
        startService(intent)
    }

    private fun showGoogleSetupDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "Google Client ID (xxxxx.apps.googleusercontent.com)"
            setText(TokenManager.get(TokenManager.KEY_G_CLIENT_ID) ?: "")
        }
        val secretInput = android.widget.EditText(this).apply {
            hint = "Google Client Secret (GOCSPX-xxxxx)"
            setText(TokenManager.get(TokenManager.KEY_G_CLIENT_SECRET) ?: "")
        }
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 20, 50, 10)
            addView(android.widget.TextView(this@MainActivity).apply {
                text = "🔑 Google Cloud Console\nhttps://console.cloud.google.com\n\n• Photos Library API 활성화\n• OAuth 2.0 클라이언트 ID 생성 (데스크톱 앱)\n• 리디렉션 URI: gphotosync://oauth/callback"
                setPadding(0, 0, 0, 20)
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
            hint = "Microsoft Application (Client) ID (xxxxxxxx-xxxx-xxxx-...)"
            setText(TokenManager.get(TokenManager.KEY_MS_CLIENT_ID) ?: "")
        }
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 20, 50, 10)
            addView(android.widget.TextView(this@MainActivity).apply {
                text = "🔑 Azure Portal\nhttps://portal.azure.com\n\n• 앱 등록 → 새 등록\n• 개인 Microsoft 계정 포함 선택\n• 리디렉션 URI: gphotosync://oauth/callback\n• Files.ReadWrite, offline_access 권한 추가"
                setPadding(0, 0, 0, 20)
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
