package com.gphotosync

import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.gphotosync.databinding.ActivityOauthBinding
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

class OAuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOauthBinding
    private val client = OkHttpClient()

    companion object {
        const val EXTRA_TYPE = "oauth_type"
        const val GOOGLE_REDIRECT_URI = "http://localhost"
        const val MS_REDIRECT_URI = "http://localhost"
        const val GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val MS_TOKEN_URL = "https://login.microsoftonline.com/common/oauth2/v2.0/token"
    }

    private var oauthType: String = "google"
    private var codeHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOauthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        oauthType = intent.getStringExtra(EXTRA_TYPE) ?: "google"
        codeHandled = false

        val authUrl = buildAuthUrl(oauthType)
        setupWebView(authUrl)
    }

    private fun getRedirectUri(): String {
        return when (oauthType) {
            "google" -> GOOGLE_REDIRECT_URI
            "microsoft" -> MS_REDIRECT_URI
            else -> GOOGLE_REDIRECT_URI
        }
    }

    private fun buildAuthUrl(type: String): String {
        return when (type) {
            "google" -> {
                val clientId = TokenManager.get(TokenManager.KEY_G_CLIENT_ID) ?: ""
                val params = mapOf(
                    "client_id"     to clientId,
                    "redirect_uri"  to getRedirectUri(),
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
                    "redirect_uri"  to getRedirectUri(),
                    "scope"         to "Files.ReadWrite offline_access",
                    "response_mode" to "query"
                )
                "https://login.microsoftonline.com/common/oauth2/v2.0/authorize?" + params.entries
                    .joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
            }
            else -> ""
        }
    }

    private fun setupWebView(authUrl: String) {
        binding.webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    if (url.startsWith("http://localhost") && url.contains("code=")) {
                        handleCallback(Uri.parse(url))
                        return true
                    }
                    return false
                }
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    if (url != null && url.startsWith("http://localhost") && url.contains("code=")) {
                        handleCallback(Uri.parse(url))
                    }
                }
            }
            loadUrl(authUrl)
        }
    }

    private fun handleCallback(uri: Uri) {
        if (codeHandled) return
        codeHandled = true

        val code = uri.getQueryParameter("code")
        if (code.isNullOrEmpty()) {
            Toast.makeText(this, "인증 실패: 코드 없음", Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        binding.webView.visibility = android.view.View.GONE
        binding.progressLayout.visibility = android.view.View.VISIBLE

        exchangeCodeForToken(code)
    }

    private fun exchangeCodeForToken(code: String) {
        val formBuilder = FormBody.Builder()
            .add("code", code)
            .add("redirect_uri", getRedirectUri())
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

        client.newCall(
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
}
