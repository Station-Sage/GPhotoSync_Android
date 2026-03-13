package com.gphotosync

import android.content.Context
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.net.URLEncoder

class OneDriveApi(private val context: Context) {

    internal fun logToFile(msg: String) {
        try {
            val f = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "sync_log.txt")
            f.appendText(msg + "\n")
        } catch (_: Exception) {}
    }

    val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
    internal val GRAPH = "https://graph.microsoft.com/v1.0"
    internal val ROOT_FOLDER = "Pictures/GooglePhotos"

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
            put("name", name); put("folder", JSONObject())
            put("@microsoft.graph.conflictBehavior", "fail")
        }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$GRAPH/me/drive/$parentPath/children")
            .header("Authorization", "Bearer $token").post(body).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { logToFile("[OD] folder onFailure: ${e.message}"); callback(false) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val respBody = it.body?.string()?.take(300); logToFile("[OD] folder resp=${it.code} $respBody")
                    if (it.code in 200..201 || it.code == 409) createFolderChain(token, parts, idx + 1, callback)
                    else callback(false)
                }
            }
        })
    }

    fun uploadFile(fileData: ByteArray, filename: String, folderPath: String, callback: (String?) -> Unit) {
        TokenManager.getValidMicrosoftToken(client) { token ->
            if (token == null) { callback(null); return@getValidMicrosoftToken }
            val encodedPath = "$folderPath/$filename".trim('/').split("/").joinToString("/") {
                URLEncoder.encode(it, "UTF-8").replace("+", "%20")
            }
            if (fileData.size <= 4 * 1024 * 1024) simpleUpload(token, fileData, encodedPath, callback)
            else chunkedUpload(token, fileData, encodedPath, callback)
        }
    }

    private fun simpleUpload(token: String, data: ByteArray, encodedPath: String, callback: (String?) -> Unit) {
        val body = data.toRequestBody("application/octet-stream".toMediaType())
        val req = Request.Builder().url("$GRAPH/me/drive/root:/$encodedPath:/content")
            .header("Authorization", "Bearer $token").put(body).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { logToFile("[OD] upload onFailure: ${e.message}"); callback(null) }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                logToFile("[OD] upload resp=${response.code} ${body?.take(200)}")
                if (response.code in 200..201 && body != null) {
                    val id = try { JSONObject(body).optString("id", "") } catch (_: Exception) { "" }
                    callback(id.ifEmpty { null })
                } else callback(null)
            }
        })
    }

    private fun chunkedUpload(token: String, data: ByteArray, encodedPath: String, callback: (String?) -> Unit) {
        val sessionBody = JSONObject().apply {
            put("item", JSONObject().apply { put("@microsoft.graph.conflictBehavior", "replace") })
        }.toString().toRequestBody("application/json".toMediaType())
        val sessionReq = Request.Builder().url("$GRAPH/me/drive/root:/$encodedPath:/createUploadSession")
            .header("Authorization", "Bearer $token").post(sessionBody).build()
        client.newCall(sessionReq).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { logToFile("[OD] chunked onFailure: ${e.message}"); callback(null) }
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) { callback(null); return }
                val uploadUrl = JSONObject(response.body?.string() ?: "{}").optString("uploadUrl", "")
                if (uploadUrl.isEmpty()) { callback(null); return }
                uploadChunks(uploadUrl, data, callback)
            }
        })
    }

    private fun uploadChunks(uploadUrl: String, data: ByteArray, callback: (String?) -> Unit) {
        val chunkSize = 10 * 1024 * 1024
        val total = data.size
        var offset = 0
        fun uploadNext() {
            if (offset >= total) { callback(null); return }
            val end = minOf(offset + chunkSize, total)
            val chunk = data.copyOfRange(offset, end)
            var retryCount = 0
            fun attemptChunk() {
                val req = Request.Builder().url(uploadUrl)
                    .header("Content-Length", chunk.size.toString())
                    .header("Content-Range", "bytes $offset-${end - 1}/$total")
                    .put(chunk.toRequestBody("application/octet-stream".toMediaType())).build()
                client.newCall(req).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        retryCount++; logToFile("[OD] chunk onFailure ($retryCount/3): ${e.message}")
                        if (retryCount < 3) { Thread.sleep(2000L * retryCount); attemptChunk() } else callback(null)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        val respBody = response.body?.string()
                        when {
                            response.code in listOf(200, 201) -> {
                                val id = try { JSONObject(respBody ?: "{}").optString("id", "") } catch (_: Exception) { "" }
                                callback(id.ifEmpty { null })
                            }
                            response.code == 202 -> { offset = end; uploadNext() }
                            response.code in 500..599 && retryCount < 3 -> {
                                retryCount++; logToFile("[OD] chunk server error ${response.code} ($retryCount/3)")
                                Thread.sleep(2000L * retryCount); attemptChunk()
                            }
                            else -> { logToFile("[OD] chunk failed code=${response.code}"); callback(null) }
                        }
                    }
                })
            }
            attemptChunk()
        }
        uploadNext()
    }

    fun uploadFileFromFile(file: java.io.File, filename: String, folderPath: String, callback: (String?) -> Unit) {
        TokenManager.getValidMicrosoftToken(client) { token ->
            if (token == null) { callback(null); return@getValidMicrosoftToken }
            val encodedPath = "$folderPath/$filename".trim('/').split("/").joinToString("/") {
                URLEncoder.encode(it, "UTF-8").replace("+", "%20")
            }
            if (file.length() <= 4 * 1024 * 1024) {
                simpleUpload(token, file.readBytes(), encodedPath, callback)
                return@getValidMicrosoftToken
            }
            val sessionBody = JSONObject().apply {
                put("item", JSONObject().apply { put("@microsoft.graph.conflictBehavior", "replace") })
            }.toString().toRequestBody("application/json".toMediaType())
            val sessionReq = Request.Builder().url("$GRAPH/me/drive/root:/$encodedPath:/createUploadSession")
                .header("Authorization", "Bearer $token").post(sessionBody).build()
            client.newCall(sessionReq).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { logToFile("[OD] fileUpload session fail: ${e.message}"); callback(null) }
                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) { callback(null); return }
                    val uploadUrl = JSONObject(response.body?.string() ?: "{}").optString("uploadUrl", "")
                    if (uploadUrl.isEmpty()) { callback(null); return }
                    uploadChunksFromFile(uploadUrl, file, callback)
                }
            })
        }
    }

    private fun uploadChunksFromFile(uploadUrl: String, file: java.io.File, callback: (String?) -> Unit) {
        val chunkSize = 10 * 1024 * 1024
        val total = file.length()
        var offset = 0L
        val fis = java.io.FileInputStream(file)
        val buf = ByteArray(chunkSize)
        fun uploadNext() {
            if (offset >= total) { fis.close(); callback(null); return }
            val remaining = (total - offset).toInt().coerceAtMost(chunkSize)
            var read = 0
            while (read < remaining) { val n = fis.read(buf, read, remaining - read); if (n == -1) break; read += n }
            val chunk = if (read == buf.size) buf else buf.copyOf(read)
            val end = offset + read
            var retryCount = 0
            fun attemptChunk() {
                val req = Request.Builder().url(uploadUrl)
                    .header("Content-Length", read.toString())
                    .header("Content-Range", "bytes $offset-${end - 1}/$total")
                    .put(chunk.toRequestBody("application/octet-stream".toMediaType())).build()
                client.newCall(req).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        retryCount++; logToFile("[OD] fileChunk onFailure ($retryCount/3): ${e.message}")
                        if (retryCount < 3) { Thread.sleep(2000L * retryCount); attemptChunk() } else { fis.close(); callback(null) }
                    }
                    override fun onResponse(call: Call, response: Response) {
                        val respBody = response.body?.string()
                        when {
                            response.code in listOf(200, 201) -> {
                                fis.close()
                                val id = try { JSONObject(respBody ?: "{}").optString("id", "") } catch (_: Exception) { "" }
                                callback(id.ifEmpty { null })
                            }
                            response.code == 202 -> { offset = end; uploadNext() }
                            response.code in 500..599 && retryCount < 3 -> {
                                retryCount++; logToFile("[OD] fileChunk server error ${response.code} ($retryCount/3)")
                                Thread.sleep(2000L * retryCount); attemptChunk()
                            }
                            else -> { logToFile("[OD] fileChunk failed code=${response.code}"); fis.close(); callback(null) }
                        }
                    }
                })
            }
            attemptChunk()
        }
        uploadNext()
    }

}
