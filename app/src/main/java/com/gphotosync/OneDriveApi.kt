package com.gphotosync

import android.content.Context
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Microsoft OneDrive (Graph API) 클라이언트
 */
class OneDriveApi(private val context: Context) {

    private fun logToFile(msg: String) {
        try {
            val f = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "sync_log.txt")
            f.appendText(msg + "\n")
        } catch (_: Exception) {}
    }

    private val client = OkHttpClient.Builder().build()
    private val GRAPH  = "https://graph.microsoft.com/v1.0"
    private val ROOT_FOLDER = "Pictures/GooglePhotos"

    /**
     * 폴더 경로가 없으면 생성
     */
    fun ensureFolder(path: String, callback: (Boolean) -> Unit) {
        TokenManager.getValidMicrosoftToken(client) { token ->
            if (token == null) { logToFile("[OD] ensureFolder token NULL"); callback(false); return@getValidMicrosoftToken }
            val parts = path.trim('/').split("/")
            createFolderChain(token, parts, 0, callback)
        }
    }

    private fun createFolderChain(token: String, parts: List<String>, idx: Int, callback: (Boolean) -> Unit) {
        if (idx >= parts.size) { callback(true); return }

        val parentPath = if (idx == 0) "root" else "root:/${parts.take(idx).joinToString("/")}:"
        val name = parts[idx]

        val body = JSONObject().apply {
            put("name", name)
            put("folder", JSONObject())
            put("@microsoft.graph.conflictBehavior", "replace")
        }.toString().toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url("$GRAPH/me/drive/$parentPath/children")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { logToFile("[OD] folder onFailure: ${e.message}"); callback(false) }
            override fun onResponse(call: Call, response: Response) {
                logToFile("[OD] folder resp=${response.code} ${response.body?.string()?.take(300)}")
                if (response.code in 200..201 || response.code == 409) {
                    createFolderChain(token, parts, idx + 1, callback)
                } else {
                    callback(false)
                }
            }
        })
    }

    /**
     * 파일을 OneDrive에 업로드 (4MB 이하: 단순 업로드, 초과: 청크 업로드)
     */
    fun uploadFile(
        fileData: ByteArray,
        filename: String,
        folderPath: String,
        callback: (Boolean) -> Unit
    ) {
        TokenManager.getValidMicrosoftToken(client) { token ->
            if (token == null) { callback(false); return@getValidMicrosoftToken }

            val safeName = URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
            val uploadPath = "$folderPath/$filename"
            val encodedPath = uploadPath.trim('/').split("/").joinToString("/") {
                URLEncoder.encode(it, "UTF-8").replace("+", "%20")
            }

            if (fileData.size <= 4 * 1024 * 1024) {
                simpleUpload(token, fileData, encodedPath, callback)
            } else {
                chunkedUpload(token, fileData, encodedPath, callback)
            }
        }
    }

    private fun simpleUpload(token: String, data: ByteArray, encodedPath: String, callback: (Boolean) -> Unit) {
        val body = data.toRequestBody("application/octet-stream".toMediaType())
        val req  = Request.Builder()
            .url("$GRAPH/me/drive/root:/$encodedPath:/content")
            .header("Authorization", "Bearer $token")
            .put(body)
            .build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { logToFile("[OD] upload onFailure: ${e.message}"); callback(false) }
            override fun onResponse(call: Call, response: Response) { logToFile("[OD] upload resp=${response.code} ${response.body?.string()?.take(200)}"); callback(response.code in 200..201) }
        })
    }

    private fun chunkedUpload(token: String, data: ByteArray, encodedPath: String, callback: (Boolean) -> Unit) {
        // 1. 업로드 세션 생성
        val sessionBody = JSONObject().apply {
            put("item", JSONObject().apply {
                put("@microsoft.graph.conflictBehavior", "replace")
            })
        }.toString().toRequestBody("application/json".toMediaType())

        val sessionReq = Request.Builder()
            .url("$GRAPH/me/drive/root:/$encodedPath:/createUploadSession")
            .header("Authorization", "Bearer $token")
            .post(sessionBody)
            .build()

        client.newCall(sessionReq).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { logToFile("[OD] chunked onFailure: ${e.message}"); callback(false) }
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) { callback(false); return }
                val uploadUrl = JSONObject(response.body?.string() ?: "{}").optString("uploadUrl", "")
                if (uploadUrl.isEmpty()) { callback(false); return }
                uploadChunks(uploadUrl, data, callback)
            }
        })
    }

    private fun uploadChunks(uploadUrl: String, data: ByteArray, callback: (Boolean) -> Unit) {
        val chunkSize = 3 * 1024 * 1024 // 3MB
        val total = data.size
        var offset = 0

        fun uploadNext() {
            if (offset >= total) { callback(true); return }
            val end   = minOf(offset + chunkSize, total)
            val chunk = data.copyOfRange(offset, end)
            val req   = Request.Builder()
                .url(uploadUrl)
                .header("Content-Length", chunk.size.toString())
                .header("Content-Range", "bytes $offset-${end - 1}/$total")
                .put(chunk.toRequestBody("application/octet-stream".toMediaType()))
                .build()

            OkHttpClient().newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { logToFile("[OD] chunked onFailure: ${e.message}"); callback(false) }
                override fun onResponse(call: Call, response: Response) {
                    if (response.code in listOf(200, 201, 202)) {
                        offset = end
                        uploadNext()
                    } else callback(false)
                }
            })
        }
        uploadNext()
    }

    val rootFolder: String get() = ROOT_FOLDER
}
