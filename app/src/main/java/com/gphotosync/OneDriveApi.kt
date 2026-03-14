package com.gphotosync

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class OneDriveApi(private val context: Context) {

    val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    internal val GRAPH = "https://graph.microsoft.com/v1.0"
    internal val ROOT_FOLDER = "Pictures/GooglePhotos"
    val rootFolder: String get() = ROOT_FOLDER

    internal fun logToFile(msg: String) {
        try { java.io.File(context.filesDir, "sync_log.txt").appendText(msg + "\n") } catch (_: Exception) {}
    }

    internal suspend fun Call.await(): Response =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { cancel() }
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isCancelled) cont.resume(
                        Response.Builder().request(request()).protocol(Protocol.HTTP_1_1)
                            .code(0).message(e.message ?: "network error")
                            .body("".toResponseBody(null)).build()
                    )
                }
                override fun onResponse(call: Call, response: Response) { cont.resume(response) }
            })
        }

    internal suspend fun getValidToken(): String? =
        suspendCancellableCoroutine { cont ->
            TokenManager.getValidMicrosoftToken(client) { token -> cont.resume(token) }
        }

    internal fun encodePath(path: String): String =
        path.trim('/').split("/").joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }

    suspend fun ensureFolder(path: String): Boolean {
        val token = getValidToken() ?: run { logToFile("[OD] ensureFolder token NULL"); return false }
        val parts = path.trim('/').split("/")
        for (idx in parts.indices) {
            val parentPath = if (idx == 0) "root" else "root:/${parts.take(idx).joinToString("/")}:"
            val body = JSONObject().apply {
                put("name", parts[idx]); put("folder", JSONObject())
                put("@microsoft.graph.conflictBehavior", "fail")
            }.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$GRAPH/me/drive/$parentPath/children")
                .header("Authorization", "Bearer $token").post(body).build()
            client.newCall(req).await().use {
                val rb = it.body?.string()?.take(300); logToFile("[OD] folder resp=${it.code} $rb")
                if (it.code !in 200..201 && it.code != 409) return false
            }
        }
        return true
    }

    suspend fun checkFileExists(filePath: String): Long? {
        val token = getValidToken() ?: return null
        val req = Request.Builder().url("$GRAPH/me/drive/root:/${encodePath(filePath)}")
            .header("Authorization", "Bearer $token").get().build()
        return client.newCall(req).await().use {
            if (it.code == 200) {
                val size = JSONObject(it.body?.string() ?: "{}").optLong("size", -1)
                if (size >= 0) size else null
            } else null
        }
    }

    suspend fun getItemId(filePath: String): String? {
        val token = getValidToken() ?: return null
        val req = Request.Builder().url("$GRAPH/me/drive/root:/${encodePath(filePath)}?\$select=id")
            .header("Authorization", "Bearer $token").get().build()
        return client.newCall(req).await().use {
            if (it.code == 200) JSONObject(it.body?.string() ?: "{}").optString("id", "").ifEmpty { null }
            else null
        }
    }

    suspend fun getFolderId(folderPath: String): String? = getItemId(folderPath)

    suspend fun listChildren(folderId: String): List<Triple<String, String, Boolean>> {
        val token = getValidToken() ?: return emptyList()
        val all = mutableListOf<Triple<String, String, Boolean>>()
        var url: String? = "$GRAPH/me/drive/items/$folderId/children?\$select=id,name,folder&\$top=1000"
        while (url != null) {
            val req = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
            client.newCall(req).await().use {
                if (it.code != 200) return all
                val json = JSONObject(it.body?.string() ?: "{}")
                val arr = json.optJSONArray("value")
                if (arr != null) for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    all.add(Triple(obj.getString("id"), obj.getString("name"), obj.has("folder")))
                }
                url = json.optString("@odata.nextLink", "").ifEmpty { null }
            }
        }
        return all
    }

    suspend fun moveFile(itemId: String, destFolderId: String): Boolean {
        val token = getValidToken() ?: return false
        val body = JSONObject().apply { put("parentReference", JSONObject().put("id", destFolderId)) }
            .toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$GRAPH/me/drive/items/$itemId")
            .header("Authorization", "Bearer $token").patch(body).build()
        return client.newCall(req).await().use {
            logToFile("[OD] moveFile resp=${it.code}")
            if (it.code == 409) logToFile("[OD] moveFile 409: 대상에 이미 존재 → 스킵")
            it.code in 200..201 || it.code == 409
        }
    }

    suspend fun deleteItem(itemId: String): Boolean {
        val token = getValidToken() ?: return false
        val req = Request.Builder().url("$GRAPH/me/drive/items/$itemId")
            .header("Authorization", "Bearer $token").delete().build()
        return client.newCall(req).await().use { it.code in 200..204 }
    }

    suspend fun listBundleChildIds(bundleId: String): Set<String>? {
        val token = getValidToken() ?: return null
        val ids = mutableSetOf<String>()
        var url: String? = "$GRAPH/me/drive/bundles/$bundleId/children?\$select=id&\$top=1000"
        while (url != null) {
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer $token").get().build()
            client.newCall(req).await().use {
                if (it.code != 200) {
                    logToFile("[OD] listBundleChildren resp=${it.code}")
                    return if (ids.isNotEmpty()) ids else null
                }
                val json = JSONObject(it.body?.string() ?: "{}")
                val arr = json.optJSONArray("value")
                if (arr != null) for (i in 0 until arr.length()) {
                    ids.add(arr.getJSONObject(i).getString("id"))
                }
                url = json.optString("@odata.nextLink", "").ifEmpty { null }
            }
        }
        logToFile("[OD] listBundleChildren: ${ids.size}개 기존 아이템")
        return ids
    }

    private suspend fun searchBundle(token: String, name: String): String? {
        try {
            val sReq = Request.Builder()
                .url("$GRAPH/me/drive/bundles?\$filter=name eq '$name'&\$select=id,name")
                .header("Authorization", "Bearer $token").get().build()
            client.newCall(sReq).await().use { sr ->
                logToFile("[OD] searchBundle '$name' resp=${sr.code}")
                if (sr.code == 200) {
                    val arr = JSONObject(sr.body?.string() ?: "{}").optJSONArray("value")
                    if (arr != null && arr.length() > 0)
                        return arr.getJSONObject(0).optString("id", "").ifEmpty { null }
                }
            }
        } catch (e: Exception) {
            logToFile("[OD] searchBundle fail '$name': ${e.message}")
        }
        return null
    }

    private suspend fun addChildrenToBundleBatch(
        token: String, bid: String, ids: List<String>
    ): Int {
        var failed = 0
        for (id in ids) {
            var success = false
            for (retry in 1..3) {
                val cb = JSONObject().put("id", id).toString()
                    .toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url("$GRAPH/me/drive/bundles/$bid/children")
                    .header("Authorization", "Bearer $token").post(cb).build()
                client.newCall(req).await().use {
                    val rb = it.body?.string()
                    logToFile("[OD] addChild resp=${it.code} ${rb?.take(200)}")
                    success = it.code in 200..204 || it.code == 409
                            || (it.code == 400 && rb?.contains("already exists") == true)
                }
                if (success) break
                logToFile("[OD] addChild retry $retry/3")
                kotlinx.coroutines.delay(2000L * retry)
            }
            if (!success) failed++
        }
        return failed
    }

    suspend fun createAlbum(name: String, childIds: List<String>): Boolean {
        if (childIds.isEmpty()) return false
        val token = getValidToken() ?: run { logToFile("[OD] createAlbum token NULL"); return false }

        var bundleId: String? = searchBundle(token, name)
        val batchSize = 20

        // 기존 앨범 → children diff 후 추가분만 처리
        if (bundleId != null) {
            val bid = bundleId!!
            val existing = listBundleChildIds(bid) ?: emptySet()
            val toAdd = childIds.filter { it !in existing }

            if (toAdd.isEmpty()) {
                logToFile("[OD] album '$name': 전체 ${childIds.size}개 이미 등록, 스킵")
                return true
            }
            logToFile("[OD] album '$name': 기존 ${existing.size}개, 추가 ${toAdd.size}개")

            for (batch in toAdd.chunked(batchSize)) {
                val fc = addChildrenToBundleBatch(token, bid, batch)
                if (fc == batch.size) {
                    logToFile("[OD] addChildrenBatch all failed"); return false
                }
            }
            return true
        }

        // 신규 앨범 생성
        val first = childIds.take(batchSize)
        val children = JSONArray(); first.forEach { children.put(JSONObject().put("id", it)) }
        val body = JSONObject().apply {
            put("name", name); put("@microsoft.graph.conflictBehavior", "fail")
            put("bundle", JSONObject().put("album", JSONObject())); put("children", children)
        }.toString().toRequestBody("application/json".toMediaType())

        var created = false
        for (retry in 1..3) {
            val req = Request.Builder().url("$GRAPH/me/drive/bundles")
                .header("Authorization", "Bearer $token").post(body).build()
            client.newCall(req).await().use {
                val rb = it.body?.string()
                logToFile("[OD] createAlbum '$name' resp=${it.code} ${rb?.take(300)}")
                when {
                    it.code in 200..201 -> {
                        bundleId = JSONObject(rb ?: "{}").optString("id", "").ifEmpty { null }
                        created = true
                    }
                    it.code == 409 -> {
                        logToFile("[OD] createAlbum 409 이름 충돌 → 기존 앨범 검색")
                        bundleId = searchBundle(token, name)
                        if (bundleId != null) {
                            // 409로 기존 앨범 발견 → diff 로직으로 처리
                            val bid2 = bundleId!!
                            val existing = listBundleChildIds(bid2) ?: emptySet()
                            val toAdd = childIds.filter { it !in existing }
                            if (toAdd.isEmpty()) {
                                logToFile("[OD] album '$name' (409→diff): 전체 이미 등록, 스킵")
                                return true
                            }
                            logToFile("[OD] album '$name' (409→diff): 추가 ${toAdd.size}개")
                            for (batch in toAdd.chunked(batchSize)) {
                                val fc = addChildrenToBundleBatch(token, bid2, batch)
                                if (fc == batch.size) return false
                            }
                            return true
                        }
                        logToFile("[OD] createAlbum 409 but searchBundle returned null")
                    }
                    it.code == 0 -> {
                        logToFile("[OD] createAlbum network error retry $retry/3")
                    }
                    else -> {
                        logToFile("[OD] createAlbum unexpected ${it.code} → fail")
                    }
                }
            }
            if (bundleId != null) break
            if (retry < 3) kotlinx.coroutines.delay(3000L * retry)
        }

        if (bundleId == null) {
            logToFile("[OD] createAlbum '$name' 최종 실패: bundleId null")
            return false
        }
        if (created && childIds.size <= batchSize) return true

        // 신규 생성 후 나머지 추가
        val bid = bundleId ?: return false
        for (batch in childIds.drop(batchSize).chunked(batchSize)) {
            val fc = addChildrenToBundleBatch(token, bid, batch)
            if (fc == batch.size) { logToFile("[OD] addChildrenBatch all failed"); return false }
        }
        return true
    }
}
