package com.gphotosync

import android.content.Context
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Google Photos Library API 클라이언트
 */
class GooglePhotosApi(private val context: Context) {

    private val client = OkHttpClient.Builder().build()
    private val BASE = "https://photoslibrary.googleapis.com/v1"

    /**
     * 모든 미디어 항목 목록 가져오기 (페이지네이션)
     * @param onPage 페이지마다 호출 (items, nextPageToken?)
     * @param onDone 완료 콜백 (totalCount)
     * @param onError 오류 콜백
     */
    fun listAllMedia(
        onPage: (List<MediaItem>, Int) -> Unit,
        onDone: (Int) -> Unit,
        onError: (String) -> Unit
    ) {
        var pageToken: String? = null
        var total = 0

        fun fetchPage() {
            TokenManager.getValidGoogleToken(client) { token ->
                if (token == null) { onError("Google 토큰 없음"); return@getValidGoogleToken }

                val urlBuilder = HttpUrl.Builder()
                    .scheme("https")
                    .host("photoslibrary.googleapis.com")
                    .addPathSegments("v1/mediaItems")
                    .addQueryParameter("pageSize", "100")
                if (pageToken != null) {
                    urlBuilder.addQueryParameter("pageToken", pageToken!!)
                }

                val req = Request.Builder()
                    .url(urlBuilder.build())
                    .header("Authorization", "Bearer $token")
                    .build()

                client.newCall(req).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) = onError(e.message ?: "네트워크 오류")
                    override fun onResponse(call: Call, response: Response) {
                        val body = response.body?.string() ?: "{}"
                        if (!response.isSuccessful) {
                            onError("Google Photos API 오류 ${response.code}: $body")
                            return
                        }
                        val json  = JSONObject(body)
                        val items = parseItems(json.optJSONArray("mediaItems"))
                        total += items.size
                        pageToken = json.optString("nextPageToken", null)
                        onPage(items, total)

                        if (pageToken.isNullOrEmpty()) {
                            onDone(total)
                        } else {
                            fetchPage() // 다음 페이지
                        }
                    }
                })
            }
        }

        fetchPage()
    }

    /**
     * 단일 미디어 항목 다운로드 (바이트 배열 반환)
     */
    fun downloadMedia(item: MediaItem, callback: (ByteArray?) -> Unit) {
        val downloadUrl = if (item.isVideo) "${item.baseUrl}=dv" else "${item.baseUrl}=d"

        TokenManager.getValidGoogleToken(client) { token ->
            if (token == null) { callback(null); return@getValidGoogleToken }

            val req = Request.Builder()
                .url(downloadUrl)
                .header("Authorization", "Bearer $token")
                .build()

            client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = callback(null)
                override fun onResponse(call: Call, response: Response) {
                    callback(if (response.isSuccessful) response.body?.bytes() else null)
                }
            })
        }
    }

    private fun parseItems(arr: JSONArray?): List<MediaItem> {
        arr ?: return emptyList()
        val list = mutableListOf<MediaItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val meta = obj.optJSONObject("mediaMetadata")
            list.add(MediaItem(
                id          = obj.getString("id"),
                filename    = obj.optString("filename", "photo_${i}.jpg"),
                baseUrl     = obj.optString("baseUrl", ""),
                mimeType    = obj.optString("mimeType", "image/jpeg"),
                createdAt   = meta?.optString("creationTime", "") ?: "",
                isVideo     = meta?.has("video") == true
            ))
        }
        return list
    }
}

data class MediaItem(
    val id: String,
    val filename: String,
    val baseUrl: String,
    val mimeType: String,
    val createdAt: String,
    val isVideo: Boolean
) {
    val yearMonth: String get() = if (createdAt.length >= 7) createdAt.substring(0, 7) else "unknown"
}
