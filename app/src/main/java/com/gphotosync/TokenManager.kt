package com.gphotosync

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * OAuth 토큰 암호화 저장 및 자동 갱신 관리자
 */
object TokenManager {

    private fun logToFile(msg: String) {
        try {
            val f = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "sync_log.txt")
            f.appendText(msg + "\n")
        } catch (_: Exception) {}
    }

    private const val PREF_NAME = "gphotosync_tokens"

    // Google
    const val KEY_G_ACCESS  = "g_access_token"
    const val KEY_G_REFRESH = "g_refresh_token"
    const val KEY_G_EXPIRY  = "g_expires_at"
    const val KEY_G_CLIENT_ID     = "g_client_id"
    const val KEY_G_CLIENT_SECRET = "g_client_secret"

    // Microsoft
    const val KEY_MS_ACCESS  = "ms_access_token"
    const val KEY_MS_REFRESH = "ms_refresh_token"
    const val KEY_MS_EXPIRY  = "ms_expires_at"
    const val KEY_MS_CLIENT_ID = "ms_client_id"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            prefs = EncryptedSharedPreferences.create(
                context,
                PREF_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // 폴백: 일반 SharedPreferences
            prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }
    }

    fun save(key: String, value: String) = prefs?.edit()?.putString(key, value)?.apply()
    fun saveLong(key: String, value: Long) = prefs?.edit()?.putLong(key, value)?.apply()
    fun get(key: String): String? = prefs?.getString(key, null)
    fun getLong(key: String): Long = prefs?.getLong(key, 0L) ?: 0L

    fun clearAll() = prefs?.edit()?.clear()?.apply()

    fun isGoogleAuthed(): Boolean {
        val access  = get(KEY_G_ACCESS) ?: return false
        val expires = getLong(KEY_G_EXPIRY)
        return access.isNotEmpty() // refresh_token으로 갱신 가능
    }

    fun isMicrosoftAuthed(): Boolean {
        val access  = get(KEY_MS_ACCESS) ?: return false
        val expires = getLong(KEY_MS_EXPIRY)
        return access.isNotEmpty()
    }

    fun getValidGoogleToken(client: OkHttpClient, callback: (String?) -> Unit) {
        val access  = get(KEY_G_ACCESS) ?: return callback(null)
        val expiry  = getLong(KEY_G_EXPIRY)
        logToFile("getValidGoogleToken: expiry=$expiry now=${System.currentTimeMillis()/1000}")
        val refresh = get(KEY_G_REFRESH)
        val clientId     = get(KEY_G_CLIENT_ID)
        val clientSecret = get(KEY_G_CLIENT_SECRET)

        logToFile("token check: now=${System.currentTimeMillis()/1000} expiry=$expiry diff=${expiry - System.currentTimeMillis()/1000}")
        if (System.currentTimeMillis() / 1000 < expiry - 300) {
            callback(access)
            return
        }

        if (refresh.isNullOrEmpty() || clientId.isNullOrEmpty() || clientSecret.isNullOrEmpty()) {
            callback(null)
            return
        }

        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("refresh_token", refresh)
            .add("grant_type", "refresh_token")
            .build()

        client.newCall(Request.Builder()
            .url("https://oauth2.googleapis.com/token")
            .post(body)
            .build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(null)
            override fun onResponse(call: Call, response: Response) {
                val json = JSONObject(response.body?.string() ?: "{}")
                    logToFile("refresh response: $json")
                if (json.has("access_token")) {
                    val newToken = json.getString("access_token")
                    val expiresIn = json.optLong("expires_in", 3600)
                    save(KEY_G_ACCESS, newToken)
                    saveLong(KEY_G_EXPIRY, System.currentTimeMillis() / 1000 + expiresIn)
                    callback(newToken)
                } else callback(null)
            }
        })
    }

    fun getValidMicrosoftToken(client: OkHttpClient, callback: (String?) -> Unit) {
        logToFile("[MS] getValidMicrosoftToken: access=${get(KEY_MS_ACCESS)?.take(10)}, refresh=${get(KEY_MS_REFRESH)?.take(10)}, clientId=${get(KEY_MS_CLIENT_ID)?.take(10)}")
        val access  = get(KEY_MS_ACCESS) ?: return callback(null)
        val expiry  = getLong(KEY_MS_EXPIRY)
        val refresh = get(KEY_MS_REFRESH)
        val clientId = get(KEY_MS_CLIENT_ID)

        logToFile("token check: now=${System.currentTimeMillis()/1000} expiry=$expiry diff=${expiry - System.currentTimeMillis()/1000}")
        if (System.currentTimeMillis() / 1000 < expiry - 300) {
            callback(access)
            return
        }

        if (refresh.isNullOrEmpty() || clientId.isNullOrEmpty()) {
            callback(null)
            return
        }

        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("refresh_token", refresh)
            .add("grant_type", "refresh_token")
            .add("scope", "Files.ReadWrite offline_access")
            .build()

        client.newCall(Request.Builder()
            .url("https://login.microsoftonline.com/consumers/oauth2/v2.0/token")
            .post(body)
            .build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(null)
            override fun onResponse(call: Call, response: Response) {
                val json = JSONObject(response.body?.string() ?: "{}")
                    logToFile("refresh response: $json")
                if (json.has("access_token")) {
                    val newToken = json.getString("access_token")
                    val expiresIn = json.optLong("expires_in", 3600)
                    save(KEY_MS_ACCESS, newToken)
                    if (json.has("refresh_token")) save(KEY_MS_REFRESH, json.getString("refresh_token"))
                    saveLong(KEY_MS_EXPIRY, System.currentTimeMillis() / 1000 + expiresIn)
                    callback(newToken)
                } else callback(null)
            }
        })
    }
}
