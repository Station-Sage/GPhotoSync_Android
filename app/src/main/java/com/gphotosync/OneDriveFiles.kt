package com.gphotosync

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.net.URLEncoder

internal fun OneDriveApi.checkFileExists(filePath: String, callback: (Long?) -> Unit) {
    TokenManager.getValidMicrosoftToken(client) { token ->
        if (token == null) { callback(null); return@getValidMicrosoftToken }
        val encodedPath = filePath.trim('/').split("/").joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        val req = Request.Builder().url("$GRAPH/me/drive/root:/$encodedPath")
            .header("Authorization", "Bearer $token").get().build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { callback(null) }
            override fun onResponse(call: Call, response: Response) {
                if (response.code == 200) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val size = json.optLong("size", -1)
                    callback(if (size >= 0) size else null)
                } else callback(null)
            }
        })
    }
    }

internal fun OneDriveApi.copyFile(srcPath: String, dstFolder: String, filename: String, callback: (Boolean) -> Unit) {
    TokenManager.getValidMicrosoftToken(client) { token ->
        if (token == null) { callback(false); return@getValidMicrosoftToken }
        val encodedSrc = srcPath.trim('/').split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val encodedDst = dstFolder.trim('/').split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val dstReq = Request.Builder().url("$GRAPH/me/drive/root:/$encodedDst").header("Authorization", "Bearer $token").get().build()
        client.newCall(dstReq).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { logToFile("[OD] copyFile dst lookup fail: ${e.message}"); callback(false) }
            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) { callback(false); return }
                    val dstId = JSONObject(resp.body?.string() ?: "{}").optString("id", "")
                    if (dstId.isEmpty()) { callback(false); return }
                    val body = JSONObject().apply {
                        put("parentReference", JSONObject().put("id", dstId)); put("name", filename)
                    }.toString().toRequestBody("application/json".toMediaType())
                    val copyReq = Request.Builder().url("$GRAPH/me/drive/root:/$encodedSrc:/copy")
                        .header("Authorization", "Bearer $token").post(body).build()
                    client.newCall(copyReq).enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) { logToFile("[OD] copyFile fail: ${e.message}"); callback(false) }
                        override fun onResponse(call: Call, response: Response) {
                            response.use { r -> logToFile("[OD] copyFile resp=${r.code}"); callback(r.code in listOf(200, 201, 202)) }
                        }
                    })
                }
            }
        })
    }
    }

internal fun OneDriveApi.getItemId(filePath: String, callback: (String?) -> Unit) {
    TokenManager.getValidMicrosoftToken(client) { token ->
        if (token == null) { callback(null); return@getValidMicrosoftToken }
        val encodedPath = filePath.trim('/').split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val httpUrl = "$GRAPH/me/drive/root:/$encodedPath".toHttpUrl().newBuilder().addQueryParameter("\$select", "id").build()
        val req = Request.Builder().url(httpUrl).header("Authorization", "Bearer $token").get().build()
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

internal fun OneDriveApi.createAlbum(name: String, childIds: List<String>, callback: (Boolean) -> Unit) {
    if (childIds.isEmpty()) { callback(false); return }
    TokenManager.getValidMicrosoftToken(client) { token ->
        if (token == null) { logToFile("[OD] createAlbum token NULL"); callback(false); return@getValidMicrosoftToken }
        val searchUrl = "$GRAPH/me/drive/bundles?\$filter=name eq '$name'&\$select=id,name"
        try {
            val searchReq = Request.Builder().url(searchUrl).header("Authorization", "Bearer $token").get().build()
            client.newCall(searchReq).execute().use { sr ->
                if (sr.code == 200) {
                    val arr = JSONObject(sr.body?.string() ?: "{}").optJSONArray("value")
                    if (arr != null && arr.length() > 0) {
                        val existingId = arr.getJSONObject(0).optString("id", "")
                        if (existingId.isNotEmpty()) {
                            logToFile("[OD] createAlbum: '$name' already exists ($existingId), adding children")
                            addChildrenToBundleBatch(token, existingId, childIds, 0, 150, callback)
                            return@getValidMicrosoftToken
                        }
                    }
                }
            }
        } catch (e: Exception) { logToFile("[OD] createAlbum search failed: ${e.message}, creating new") }
        val batchSize = 150
        val firstBatch = childIds.take(batchSize); val remaining = childIds.drop(batchSize)
        val children = JSONArray()
        for (id in firstBatch) children.put(JSONObject().put("id", id))
        val body = JSONObject().apply {
            put("name", name); put("@microsoft.graph.conflictBehavior", "fail")
            put("bundle", JSONObject().put("album", JSONObject())); put("children", children)
        }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$GRAPH/me/drive/bundles").header("Authorization", "Bearer $token").post(body).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { logToFile("[OD] createAlbum fail: ${e.message}"); callback(false) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val respBody = it.body?.string()
                    logToFile("[OD] createAlbum resp=${it.code} ${respBody?.take(300)}")
                    if (it.code !in 200..201) { callback(false); return }
                    if (remaining.isEmpty()) { callback(true); return }
                    val bundleId = try { JSONObject(respBody ?: "{}").optString("id", "") } catch (_: Exception) { "" }
                    if (bundleId.isEmpty()) { callback(true); return }
                    addChildrenToBundleBatch(token, bundleId, remaining, 0, batchSize, callback)
                }
            }
        })
    }
    }

private fun OneDriveApi.addChildrenToBundleBatch(token: String, bundleId: String, ids: List<String>, offset: Int, batchSize: Int, callback: (Boolean) -> Unit) {
    if (offset >= ids.size) { callback(true); return }
    val batch = ids.subList(offset, minOf(offset + batchSize, ids.size))
    var failCount = 0
    for (id in batch) {
        val childBody = JSONObject().apply { put("id", id) }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$GRAPH/me/drive/items/$bundleId/children").header("Authorization", "Bearer $token").post(childBody).build()
        try { client.newCall(req).execute().use { resp -> logToFile("[OD] addChild resp=${resp.code}"); if (resp.code !in 200..204) failCount++ } }
        catch (e: Exception) { logToFile("[OD] addChild fail: ${e.message}"); failCount++ }
    }
    if (failCount == batch.size) { logToFile("[OD] addChildrenBatch all failed at offset=$offset"); callback(false) }
    else addChildrenToBundleBatch(token, bundleId, ids, offset + batchSize, batchSize, callback)
    }

internal fun OneDriveApi.moveFile(itemId: String, destFolderId: String, callback: (Boolean) -> Unit) {
    TokenManager.getValidMicrosoftToken(client) { token ->
        if (token == null) { callback(false); return@getValidMicrosoftToken }
        val body = JSONObject().apply { put("parentReference", JSONObject().put("id", destFolderId)) }
            .toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$GRAPH/me/drive/items/$itemId").header("Authorization", "Bearer $token").patch(body).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { logToFile("[OD] moveFile fail: ${e.message}"); callback(false) }
            override fun onResponse(call: Call, response: Response) { response.use { logToFile("[OD] moveFile resp=${it.code}"); callback(it.code in 200..201) } }
        })
    }
    }

internal fun OneDriveApi.listChildren(folderId: String, callback: (List<Triple<String, String, Boolean>>?) -> Unit) {
    TokenManager.getValidMicrosoftToken(client) { token ->
        if (token == null) { callback(null); return@getValidMicrosoftToken }
        val allItems = mutableListOf<Triple<String, String, Boolean>>()
        fun fetchPage(url: String) {
            val req = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
            client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { callback(if (allItems.isNotEmpty()) allItems else null) }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (it.code != 200) { callback(if (allItems.isNotEmpty()) allItems else null); return }
                        val body = it.body?.string() ?: "{}"
                        val json = try { JSONObject(body) } catch (_: Exception) { callback(if (allItems.isNotEmpty()) allItems else null); return }
                        val arr = json.optJSONArray("value")
                        if (arr != null) { for (i in 0 until arr.length()) { val obj = arr.getJSONObject(i); allItems.add(Triple(obj.getString("id"), obj.getString("name"), obj.has("folder"))) } }
                        val nextLink = json.optString("@odata.nextLink", "")
                        if (nextLink.isNotEmpty()) fetchPage(nextLink) else callback(allItems)
                    }
                }
            })
        }
        fetchPage("$GRAPH/me/drive/items/$folderId/children?\$select=id,name,folder&\$top=1000")
    }
    }

internal fun OneDriveApi.deleteItem(itemId: String, callback: (Boolean) -> Unit) {
    TokenManager.getValidMicrosoftToken(client) { token ->
        if (token == null) { callback(false); return@getValidMicrosoftToken }
        val req = Request.Builder().url("$GRAPH/me/drive/items/$itemId").header("Authorization", "Bearer $token").delete().build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { callback(false) }
            override fun onResponse(call: Call, response: Response) { response.use { callback(it.code in 200..204) } }
        })
    }
    }

internal fun OneDriveApi.getFolderId(folderPath: String, callback: (String?) -> Unit) {
    TokenManager.getValidMicrosoftToken(client) { token ->
        if (token == null) { callback(null); return@getValidMicrosoftToken }
        val encodedPath = folderPath.trim('/').split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val req = Request.Builder().url("$GRAPH/me/drive/root:/$encodedPath?\$select=id").header("Authorization", "Bearer $token").get().build()
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

internal val OneDriveApi.rootFolder: String get() = ROOT_FOLDER
