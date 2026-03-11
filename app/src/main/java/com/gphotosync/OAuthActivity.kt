package com.gphotosync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.gphotosync.databinding.ActivityOauthBinding
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest

class OAuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOauthBinding
    private val httpClient = OkHttpClient()

    companion object {
        const val EXTRA_TYPE = "oauth_type"
        const val GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val MS_TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
        private const val REDIRECT_URI = "http://localhost"
    }

    private var oauthType: String = "google"
    private var codeHandled = false
    private var codeVerifier: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOauthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        oauthType = intent.getStringExtra(EXTRA_TYPE) ?: "google"
        codeHandled = false

        val authUrl = buildAuthUrl(oauthType)
        binding.webView.settings.javaScriptEnabled = true
        binding.webView.settings.domStorageEnabled = true
        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("http://localhost")) {
                    if (!codeHandled) {
                        codeHandled = true
                        val code = Uri.parse(url).getQueryParameter("code")
                        if (!code.isNullOrEmpty()) {
                            binding.webView.visibility = android.view.View.GONE
                            binding.progressLayout.visibility = android.view.View.VISIBLE
                            exchangeCodeForToken(code)
                        } else {
                            Toast.makeText(this@OAuthActivity, "인증 코드를 받지 못했습니다", Toast.LENGTH_LONG).show()
                            finish()
                        }
                    }
                    return true
                }
                return false
            }
        }
        binding.webView.loadUrl(authUrl)
    }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun buildAuthUrl(type: String): String {
        return when (type) {
            "google" -> {
                val clientId = TokenManager.get(TokenManager.KEY_G_CLIENT_ID) ?: ""
                val params = mapOf(
                    "client_id" to clientId,
                    "redirect_uri" to REDIRECT_URI,
                    "response_type" to "code",
                    "scope" to "https://www.googleapis.com/auth/photospicker.mediaitems.readonly",
                    "access_type" to "offline",
                    "prompt" to "consent"
                )
                "https://accounts.google.com/o/oauth2/v2/auth?" + params.entries
                    .joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
            }
            "microsoft" -> {
                val clientId = TokenManager.get(TokenManager.KEY_MS_CLIENT_ID) ?: ""
                codeVerifier = generateCodeVerifier()
                val codeChallenge = generateCodeChallenge(codeVerifier)
                val params = mapOf(
                    "client_id" to clientId,
                    "response_type" to "code",
                    "redirect_uri" to REDIRECT_URI,
                    "scope" to "Files.ReadWrite offline_access",
                    "response_mode" to "query",
                    "code_challenge" to codeChallenge,
                    "code_challenge_method" to "S256"
                )
                "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize?" + params.entries
                    .joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
            }
            else -> ""
        }
    }

    private fun extractCode(input: String): String {
        if (input.contains("code=")) {
            val uri = Uri.parse(input)
            val code = uri.getQueryParameter("code")
            if (!code.isNullOrEmpty()) return code
            val start = input.indexOf("code=") + 5
            val end = input.indexOf("&", start).let { if (it == -1) input.length else it }
            return input.substring(start, end)
        }
        return input
    }

    private fun exchangeCodeForToken(code: String) {
        if (codeHandled) return
        codeHandled = true

        Toast.makeText(this, "토큰 교환 중...", Toast.LENGTH_SHORT).show()

        val formBuilder = FormBody.Builder()
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("grant_type", "authorization_code")

        val tokenUrl: String
        when (oauthType) {
            "google" -> {
                tokenUrl = GOOGLE_TOKEN_URL
                formBuilder.add("client_id", TokenManager.get(TokenManager.KEY_G_CLIENT_ID) ?: "")
                formBuilder.add("client_secret", TokenManager.get(TokenManager.KEY_G_CLIENT_SECRET) ?: "")
            }
            "microsoft" -> {
                tokenUrl = MS_TOKEN_URL
                formBuilder.add("client_id", TokenManager.get(TokenManager.KEY_MS_CLIENT_ID) ?: "")
                formBuilder.add("code_verifier", codeVerifier)
                formBuilder.add("client_secret", "qtyfaBBYA403=unZUP40~_#")
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
                        Toast.makeText(this@OAuthActivity, "인증 성공!", Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK)
                        finish()
                    } else {
                        val error = json.optString("error_description", json.optString("error", body))
                        Toast.makeText(this@OAuthActivity, "토큰 오류: $error", Toast.LENGTH_LONG).show()
                        setResult(RESULT_CANCELED)
                        finish()
                    }
                }
            }
        })
    }

    private fun saveTokens(json: JSONObject) {
        val access = json.getString("access_token")
        val refresh = json.optString("refresh_token", "")
        val expiresIn = json.optLong("expires_in", 3600)
        val expiry = System.currentTimeMillis() / 1000 + expiresIn

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
}
