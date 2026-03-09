package com.gphotosync

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import com.gphotosync.databinding.ActivityOauthBinding
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.net.ServerSocket
import kotlin.concurrent.thread

class OAuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOauthBinding
    private val httpClient = OkHttpClient()
    private var serverSocket: ServerSocket? = null

    companion object {
        const val EXTRA_TYPE = "oauth_type"
        const val GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val MS_TOKEN_URL = "https://login.microsoftonline.com/common/oauth2/v2.0/token"
    }

    private var oauthType: String = "google"
    private var redirectUri: String = ""
    private var codeHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOauthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        oauthType = intent.getStringExtra(EXTRA_TYPE) ?: "google"
        codeHandled = false

        // intent-filter로 돌아온 경우 (gphotosync://oauth?code=...)
        intent.data?.let { uri ->
            val code = uri.getQueryParameter("code")
            if (!code.isNullOrEmpty()) {
                redirectUri = "gphotosync://oauth/callback"
                handleCode(code)
                return
            }
        }

        startLoopbackOAuth()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.data?.let { uri ->
            val code = uri.getQueryParameter("code")
            if (!code.isNullOrEmpty() && !codeHandled) {
                redirectUri = "gphotosync://oauth/callback"
                handleCode(code)
            }
        }
    }

    private fun startLoopbackOAuth() {
        binding.progressLayout.visibility = android.view.View.VISIBLE
        binding.webView.visibility = android.view.View.GONE

        thread {
            try {
                serverSocket = ServerSocket(0) // 랜덤 포트
                val port = serverSocket!!.localPort
                redirectUri = "http://127.0.0.1:$port"

                runOnUiThread {
                    val authUrl = buildAuthUrl(oauthType)
                    try {
                        val customTabsIntent = CustomTabsIntent.Builder().build()
                        customTabsIntent.launchUrl(this, Uri.parse(authUrl))
                    } catch (e: Exception) {
                        // Custom Tab 실패시 일반 브라우저
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
                        startActivity(browserIntent)
                    }
                }

                // 로컬 서버에서 콜백 대기
                val socket = serverSocket!!.accept()
                val reader = socket.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: ""

                // GET /?code=xxx&scope=... HTTP/1.1
                val path = requestLine.split(" ").getOrNull(1) ?: ""
                val uri = Uri.parse("http://localhost$path")
                val code = uri.getQueryParameter("code")

                // 브라우저에 완료 페이지 표시
                val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n\r\n" +
                    "<html><body style='text-align:center;padding:50px;font-family:sans-serif;'>" +
                    "<h2>✅ 인증 완료!</h2><p>앱으로 돌아가세요.</p>" +
                    "<script>window.close();</script></body></html>"
                socket.getOutputStream().write(response.toByteArray())
                socket.close()
                serverSocket?.close()

                if (!code.isNullOrEmpty()) {
                    runOnUiThread { handleCode(code) }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "인증 실패: 코드 없음", Toast.LENGTH_LONG).show()
                        setResult(RESULT_CANCELED)
                        finish()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "서버 오류: ${e.message}", Toast.LENGTH_LONG).show()
                    setResult(RESULT_CANCELED)
                    finish()
                }
            }
        }
    }

    private fun buildAuthUrl(type: String): String {
        return when (type) {
            "google" -> {
                val clientId = TokenManager.get(TokenManager.KEY_G_CLIENT_ID) ?: ""
                val params = mapOf(
                    "client_id"     to clientId,
                    "redirect_uri"  to redirectUri,
                    "response_type" to "code",
                    "scope"         to "https://www.googleapis.com/auth/photoslibrary.readonly",
                    "access_type"   to "offline",
                    "prompt"        to "consent"
                )
                "https://accounts.google.com/o/oauth2/v2/auth?" + params.entries
                    .joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
            }
            "microsoft" -> {
                val clientId = TokenManager.get(TokenManager.KEY_MS_CLIENT_ID) ?: ""
                val params = mapOf(
                    "client_id"     to clientId,
                    "response_type" to "code",
                    "redirect_uri"  to redirectUri,
                    "scope"         to "Files.ReadWrite offline_access",
                    "response_mode" to "query"
                )
                "https://login.microsoftonline.com/common/oauth2/v2.0/authorize?" + params.entries
                    .joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
            }
            else -> ""
        }
    }

    private fun handleCode(code: String) {
        if (codeHandled) return
        codeHandled = true

        binding.webView.visibility = android.view.View.GONE
        binding.progressLayout.visibility = android.view.View.VISIBLE

        exchangeCodeForToken(code)
    }

    private fun exchangeCodeForToken(code: String) {
        val formBuilder = FormBody.Builder()
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("grant_type", "authorization_code")

        val tokenUrl: String
        when (oauthType) {
            "google" -> {
                tokenUrl = GOOGLE_TOKEN_URL
                val clientId     = TokenManager.get(TokenManager.KEY_G_CLIENT_ID) ?: ""
                val clientSecret = TokenManager.get(TokenManager.KEY_G_CLIENT_SECRET) ?: ""
                formBuilder.add("client_id", clientId)
                formBuilder.add("client_secret", clientSecret)
            }
            "microsoft" -> {
                tokenUrl = MS_TOKEN_URL
                val clientId = TokenManager.get(TokenManager.KEY_MS_CLIENT_ID) ?: ""
                formBuilder.add("client_id", clientId)
                formBuilder.add("scope", "Files.ReadWrite offline_access")
            }
            else -> return
        }

        httpClient.newCall(
            Request.Builder().url(tokenUrl).post(formBuilder.build()).build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@OAuthActivity, "토큰 교환 실패: ${e.message}", Toast.LENGTH_LONG).show()
                    setResult(RESULT_CANCELED)
                    finish()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: "{}"
                val json = JSONObject(body)

                runOnUiThread {
                    if (json.has("access_token")) {
                        saveTokens(json)
                        Toast.makeText(this@OAuthActivity, "인증 완료!", Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK)
                        finish()
                    } else {
                        Toast.makeText(this@OAuthActivity, "토큰 오류: $body", Toast.LENGTH_LONG).show()
                        setResult(RESULT_CANCELED)
                        finish()
                    }
                }
            }
        })
    }

    private fun saveTokens(json: JSONObject) {
        val access    = json.getString("access_token")
        val refresh   = json.optString("refresh_token", "")
        val expiresIn = json.optLong("expires_in", 3600)
        val expiry    = System.currentTimeMillis() / 1000 + expiresIn

        when (oauthType) {
            "google" -> {
                TokenManager.save(TokenManager.KEY_G_ACCESS, access)
                if (refresh.isNotEmpty()) TokenManager.save(TokenManager.KEY_G_REFRESH, refresh)
                TokenManager.saveLong(TokenManager.KEY_G_EXPIRY, expiry)
            }
            "microsoft" -> {
                TokenManager.save(TokenManager.KEY_MS_ACCESS, access)
                if (refresh.isNotEmpty()) TokenManager.save(TokenManager.KEY_MS_REFRESH, refresh)
                TokenManager.saveLong(TokenManager.KEY_MS_EXPIRY, expiry)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { serverSocket?.close() } catch (_: Exception) {}
    }
}
