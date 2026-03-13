package com.gphotosync

import android.content.Context
import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

data class MediaItem(
    val id: String,
    val filename: String,
    val baseUrl: String,
    val mimeType: String,
    val createdAt: String,
    val isVideo: Boolean
) {
    val yearMonth: String get() = if (createdAt.length >= 7) createdAt.substring(0, 7) else "unknown"
    val year: String get() = if (createdAt.length >= 4) createdAt.substring(0, 4) else "unknown"
}

class GooglePhotosApi(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val PICKER_BASE = "https://photospicker.googleapis.com/v1"

    private fun logToFile(msg: String) {
        try {
            val f = java.io.File(context.filesDir, "sync_log.txt")
            f.appendText(msg + "\n")
        } catch (_: Exception) {}
    }

    /** Picker API: 세션 생성 */
    fun createSession(callback: (sessionId: String?, pickerUri: String?) -> Unit) {
        TokenManager.getValidGoogleToken(client) { token ->
            if (token == null) { logToFile("[Picker] token is null"); callback(null, null); return@getValidGoogleToken }

            val req = Request.Builder()
                .url("$PICKER_BASE/sessions")
                .header("Authorization", "Bearer $token")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    logToFile("[Picker] createSession failed: ${e.message}")
                    callback(null, null)
                }
                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string() ?: "{}"
                    logToFile("[Picker] createSession resp=${response.code} body=${body.take(300)}")
                    if (!response.isSuccessful) { callback(null, null); return }
                    val json = JSONObject(body)
                    callback(json.optString("id"), json.optString("pickerUri"))
                }
            })
        }
    }

    /** Picker API: 세션 상태 폴링 */
    fun pollSession(sessionId: String, callback: (mediaItemsSet: Boolean) -> Unit) {
        TokenManager.getValidGoogleToken(client) { token ->
            if (token == null) { callback(false); return@getValidGoogleToken }

            val req = Request.Builder()
                .url("$PICKER_BASE/sessions/$sessionId")
                .header("Authorization", "Bearer $token")
                .build()

            client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { callback(false) }
                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string() ?: "{}"
                    logToFile("[Picker] pollSession resp=${response.code} body=${body.take(300)}")
                    val json = JSONObject(body)
                    callback(json.optBoolean("mediaItemsSet", false))
                }
            })
        }
    }

    /** Picker API: 선택된 미디어 아이템 목록 */
    fun listPickedMedia(sessionId: String, onDone: (List<MediaItem>) -> Unit, onError: (String) -> Unit) {
        val allItems = mutableListOf<MediaItem>()
        var pageToken: String? = null

        fun fetchPage() {
            TokenManager.getValidGoogleToken(client) { token ->
                if (token == null) { onError("토큰 없음"); return@getValidGoogleToken }

                val urlBuilder = HttpUrl.Builder()
                    .scheme("https")
                    .host("photospicker.googleapis.com")
                    .addPathSegments("v1/mediaItems")
                    .addQueryParameter("sessionId", sessionId)
                    .addQueryParameter("pageSize", "100")
                if (pageToken != null) {
                    urlBuilder.addQueryParameter("pageToken", pageToken!!)
                }

                val req = Request.Builder()
                    .url(urlBuilder.build())
                    .header("Authorization", "Bearer $token")
                    .build()

                client.newCall(req).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { onError(e.message ?: "네트워크 오류") }
                    override fun onResponse(call: Call, response: Response) {
                        val body = response.body?.string() ?: "{}"
                        logToFile("[Picker] listMedia resp=${response.code} body=${body.take(500)}")
                        if (!response.isSuccessful) { onError("API 오류 ${response.code}: $body"); return }

                        val json = JSONObject(body)
                        val arr = json.optJSONArray("mediaItems")
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                val mediaFile = obj.optJSONObject("mediaFile")
                                val isVideo = obj.optString("type") == "VIDEO"
                                allItems.add(MediaItem(
                                    id = obj.getString("id"),
                                    filename = mediaFile?.optString("filename", "photo_$i.jpg") ?: "photo_$i.jpg",
                                    baseUrl = mediaFile?.optString("baseUrl", "") ?: "",
                                    mimeType = mediaFile?.optString("mimeType", "image/jpeg") ?: "image/jpeg",
                                    createdAt = obj.optString("createTime", ""),
                                    isVideo = isVideo
                                ))
                            }
                        }
                        pageToken = json.optString("nextPageToken", null)
                        if (pageToken.isNullOrEmpty()) {
                            logToFile("[Picker] total picked items: ${allItems.size}")
                            onDone(allItems)
                        } else {
                            fetchPage()
                        }
                    }
                })
            }
        }
        fetchPage()
    }

    /** 미디어 다운로드 */
    fun downloadMedia(item: MediaItem, callback: (ByteArray?) -> Unit) {
        val downloadUrl = if (item.isVideo) "${item.baseUrl}=dv" else "${item.baseUrl}=d"

        TokenManager.getValidGoogleToken(client) { token ->
            if (token == null) { callback(null); return@getValidGoogleToken }

            val req = Request.Builder()
                .url(downloadUrl)
                .header("Authorization", "Bearer $token")
                .build()

            client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { logToFile("[DL] FAIL ${item.filename}: ${e.message}"); callback(null) }
                override fun onResponse(call: Call, response: Response) {
                    val bytes = if (response.isSuccessful) response.body?.bytes() else null
                    logToFile("[DL] ${item.filename} code=${response.code} size=${bytes?.size ?: 0}")
                    callback(bytes)
                }
            })
        }
    }

    /** 세션 삭제 */
    fun deleteSession(sessionId: String) {
        TokenManager.getValidGoogleToken(client) { token ->
            if (token == null) return@getValidGoogleToken
            val req = Request.Builder()
                .url("$PICKER_BASE/sessions/$sessionId")
                .header("Authorization", "Bearer $token")
                .delete()
                .build()
            client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) {
                    logToFile("[Picker] deleteSession resp=${response.code}")
                }
            })
        }
    }
}
