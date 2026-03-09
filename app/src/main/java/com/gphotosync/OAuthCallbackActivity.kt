package com.gphotosync

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * 딥링크 콜백 수신 Activity
 * gphotosync://oauth/callback?code=...  →  OAuthActivity로 전달
 */
class OAuthCallbackActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data: Uri? = intent?.data
        if (data != null) {
            // OAuthActivity가 WebView로 처리하므로 일반적으로 여기까지 오지 않음
            // 혹시 외부 브라우저를 통한 리디렉션 시 MainActivity로 전달
            val fwd = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("oauth_callback_uri", data.toString())
            }
            startActivity(fwd)
        }
        finish()
    }
}
