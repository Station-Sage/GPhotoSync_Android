package com.gphotosync

import android.content.Context
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
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
            put("@microsoft.graph.conflictBehavior", "fail")
        }.toString().toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url("$GRAPH/me/drive/$parentPath/children")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { logToFile("[OD] folder onFailure: ${e.message}"); callback(false) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    logToFile("[OD] folder resp=${it.code} ${it.body?.string()?.take(300)}")
                    if (it.code in 200..201 || it.code == 409) {
                        createFolderChain(token, parts, idx + 1, callback)
                    } else {
                        callback(false)
                    }
                }
            }
        })
    }

    /**
     * 파일을 OneDrive에 업로드 (4MB 이하: 단순 업로드, 초과: 청크 업로드)
     */
    /**
     * 파일 업로드 후 driveItem id 반환 (실패 시 null)
     */
    fun uploadFile(
        fileData: ByteArray,
        filename: String,
        folderPath: String,
        callback: (String?) -> Unit
    ) {
        TokenManager.getValidMicrosoftToken(client) { token ->
            if (token == null) { callback(null); return@getValidMicrosoftToken }

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

    private fun simpleUpload(token: String, data: ByteArray, encodedPath: String, callback: (String?) -> Unit) {
        val body = data.toRequestBody("application/octet-stream".toMediaType())
        val req  = Request.Builder()
            .url("$GRAPH/me/drive/root:/$encodedPath:/content")
            .header("Authorization", "Bearer $token")
            .put(body)
            .build()

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
        val chunkSize = 10 * 1024 * 1024 // 10MB
        val total = data.size
        var offset = 0

        fun uploadNext() {
            if (offset >= total) { callback(null); return } // should not reach here
            val end   = minOf(offset + chunkSize, total)
            val chunk = data.copyOfRange(offset, end)
            val req   = Request.Builder()
                .url(uploadUrl)
                .header("Content-Length", chunk.size.toString())
                .header("Content-Range", "bytes $offset-${end - 1}/$total")
                .put(chunk.toRequestBody("application/octet-stream".toMediaType()))
                .build()

            client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { logToFile("[OD] chunked onFailure: ${e.message}"); callback(null) }
                override fun onResponse(call: Call, response: Response) {
                    val respBody = response.body?.string()
                    if (response.code in listOf(200, 201)) {
                        // 완료 — id 추출
                        val id = try { JSONObject(respBody ?: "{}").optString("id", "") } catch (_: Exception) { "" }
                        callback(id.ifEmpty { null })
                    } else if (response.code == 202) {
                        offset = end
                        uploadNext()
                    } else callback(null)
                }
            })
        }
        uploadNext()
    }

    /**
     * OneDrive에 해당 경로의 파일이 존재하는지 확인하고, 존재하면 파일 크기를 반환
     */
    fun checkFileExists(filePath: String, callback: (Long?) -> Unit) {
        TokenManager.getValidMicrosoftToken(client) { token ->
            if (token == null) { callback(null); return@getValidMicrosoftToken }

            val encodedPath = filePath.trim('/').split("/").joinToString("/") {
                java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
            }

            val req = Request.Builder()
                .url("$GRAPH/me/drive/root:/$encodedPath")
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { callback(null) }
                override fun onResponse(call: Call, response: Response) {
                    if (response.code == 200) {
                        val json = JSONObject(response.body?.string() ?: "{}")
                        val size = json.optLong("size", -1)
                        callback(if (size >= 0) size else null)
                    } else {
                        callback(null)
                    }
                }
            })
        }
    }


    /**
     * OneDrive에서 파일을 다른 폴더로 복사
     */
    fun copyFile(srcPath: String, dstFolder: String, filename: String, callback: (Boolean) -> Unit) {
        TokenManager.getValidMicrosoftToken(client) { token ->
            if (token == null) { callback(false); return@getValidMicrosoftToken }

            val encodedSrc = srcPath.trim('/').split("/").joinToString("/") {
                URLEncoder.encode(it, "UTF-8").replace("+", "%20")
            }

            // 먼저 대상 폴더의 driveItem ID를 가져와야 함
            val encodedDst = dstFolder.trim('/').split("/").joinToString("/") {
                URLEncoder.encode(it, "UTF-8").replace("+", "%20")
            }

            val dstReq = Request.Builder()
                .url("$GRAPH/me/drive/root:/$encodedDst")
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            client.newCall(dstReq).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { logToFile("[OD] copyFile dst lookup fail: ${e.message}"); callback(false) }
                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        if (!resp.isSuccessful) { callback(false); return }
                        val dstId = JSONObject(resp.body?.string() ?: "{}").optString("id", "")
                        if (dstId.isEmpty()) { callback(false); return }

                        // copy 요청
                        val body = JSONObject().apply {
                            put("parentReference", JSONObject().put("id", dstId))
                            put("name", filename)
                        }.toString().toRequestBody("application/json".toMediaType())

                        val copyReq = Request.Builder()
                            .url("$GRAPH/me/drive/root:/$encodedSrc:/copy")
                            .header("Authorization", "Bearer $token")
                            .post(body)
                            .build()

                        client.newCall(copyReq).enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) { logToFile("[OD] copyFile fail: ${e.message}"); callback(false) }
                            override fun onResponse(call: Call, response: Response) {
                                response.use { r ->
                                    logToFile("[OD] copyFile resp=${r.code}")
                                    // copy는 202 Accepted (비동기) 또는 201
                                    callback(r.code in listOf(200, 201, 202))
                                }
                            }
                        })
                    }
                }
            })
        }
    }


    /**
     * 경로로 driveItem id 조회
     */
    fun getItemId(filePath: String, callback: (String?) -> Unit) {
        TokenManager.getValidMicrosoftToken(client) { token ->
            if (token == null) { callback(null); return@getValidMicrosoftToken }
            val encodedPath = filePath.trim('/').split("/").joinToString("/") {
                URLEncoder.encode(it, "UTF-8").replace("+", "%20")
            }
            val itemUrl = "$GRAPH/me/drive/root:/$encodedPath"
            val httpUrl = okhttp3.HttpUrl.parse(itemUrl)!!.newBuilder()
                .addQueryParameter("\$select", "id")
                .build()
            val req = Request.Builder()
                .url(httpUrl)
                .header("Authorization", "Bearer $token")
                .get().build()
            client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { callback(null) }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (it.code == 200) {
                            val id = try { JSONObject(it.body?.string() ?: "{}").optString("id", "") } catch (_: Exception) { "" }
                            callback(id.ifEmpty { null })
                        } else callback(null)
                    }
                }
            })
        }
    }

    /**
     * OneDrive 앨범(bundle) 생성 — 용량 0, 참조만
     * @param name 앨범 이름
     * @param childIds driveItem id 목록
     */
    /**
     * OneDrive 앨범(bundle) 생성 — 용량 0, 참조만
     * 200개 초과 시 먼저 생성 후 나머지를 PATCH로 추가
     */
    fun createAlbum(name: String, childIds: List<String>, callback: (Boolean) -> Unit) {
        if (childIds.isEmpty()) { callback(false); return }
        TokenManager.getValidMicrosoftToken(client) { token ->
            if (token == null) { logToFile("[OD] createAlbum token NULL"); callback(false); return@getValidMicrosoftToken }

            val batchSize = 150
            val firstBatch = childIds.take(batchSize)
            val remaining = childIds.drop(batchSize)

            val children = JSONArray()
            for (id in firstBatch) { children.put(JSONObject().put("id", id)) }
            val body = JSONObject().apply {
                put("name", name)
                put("@microsoft.graph.conflictBehavior", "rename")
                put("bundle", JSONObject().put("album", JSONObject()))
                put("children", children)
            }.toString().toRequestBody("application/json".toMediaType())

            val req = Request.Builder()
                .url("$GRAPH/me/drive/bundles")
                .header("Authorization", "Bearer $token")
                .post(body).build()

            client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { logToFile("[OD] createAlbum fail: ${e.message}"); callback(false) }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val respBody = it.body?.string()
                        logToFile("[OD] createAlbum resp=${it.code} ${respBody?.take(300)}")
                        if (it.code !in 200..201) { callback(false); return }

                        if (remaining.isEmpty()) { callback(true); return }

                        // 나머지를 PATCH로 추가
                        val bundleId = try { JSONObject(respBody ?: "{}").optString("id", "") } catch (_: Exception) { "" }
                        if (bundleId.isEmpty()) { callback(true); return } // 생성은 됐으므로 true

                        addChildrenToBundleBatch(token, bundleId, remaining, 0, batchSize, callback)
                    }
                }
            })
        }
    }

    private fun addChildrenToBundleBatch(token: String, bundleId: String, ids: List<String>, offset: Int, batchSize: Int, callback: (Boolean) -> Unit) {
        if (offset >= ids.size) { callback(true); return }
        val batch = ids.subList(offset, minOf(offset + batchSize, ids.size))
        val children = JSONArray()
        for (id in batch) { children.put(JSONObject().put("id", id)) }
        val body = JSONObject().apply {
            put("children@odata.bind", children)
        }

        // POST children to bundle
        for (id in batch) {
            val childBody = JSONObject().put("id", id).toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("$GRAPH/me/drive/items/$bundleId/children/\$ref")
                .header("Authorization", "Bearer $token")
                .post(childBody).build()
            // 동기 호출 (이미 백그라운드 스레드)
            try {
                client.newCall(req).execute().use { resp ->
                    logToFile("[OD] addChild resp=${resp.code}")
                }
            } catch (e: Exception) {
                logToFile("[OD] addChild fail: ${e.message}")
            }
        }
        addChildrenToBundleBatch(token, bundleId, ids, offset + batchSize, batchSize, callback)
    }

    val rootFolder: String get() = ROOT_FOLDER
}
