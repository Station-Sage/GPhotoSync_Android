package com.gphotosync

import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private const val SIMPLE_LIMIT = 4 * 1024 * 1024
private const val CHUNK_SIZE = 10 * 1024 * 1024
private const val MAX_RETRIES = 3

internal sealed interface ChunkSource {
    val totalSize: Long
    fun readChunk(offset: Long, size: Int): ByteArray
    fun close() {}

    class Memory(private val data: ByteArray) : ChunkSource {
        override val totalSize = data.size.toLong()
        override fun readChunk(offset: Long, size: Int): ByteArray =
            data.copyOfRange(offset.toInt(), offset.toInt() + size)
    }

    class FileSource(private val file: java.io.File) : ChunkSource {
        override val totalSize = file.length()
        private val raf = java.io.RandomAccessFile(file, "r")
        override fun readChunk(offset: Long, size: Int): ByteArray {
            val buf = ByteArray(size); raf.seek(offset)
            var read = 0
            while (read < size) { val n = raf.read(buf, read, size - read); if (n == -1) break; read += n }
            return if (read == size) buf else buf.copyOf(read)
        }
        override fun close() { try { raf.close() } catch (_: Exception) {} }
    }
}

suspend fun OneDriveApi.uploadFile(fileData: ByteArray, filename: String, folderPath: String): String? {
    val token = getValidToken() ?: return null
    val path = encodePath("$folderPath/$filename")
    return if (fileData.size <= SIMPLE_LIMIT) simpleUpload(token, fileData, path)
    else chunkedUpload(token, ChunkSource.Memory(fileData), path)
}

suspend fun OneDriveApi.uploadFileFromFile(file: java.io.File, filename: String, folderPath: String): String? {
    val token = getValidToken() ?: return null
    val path = encodePath("$folderPath/$filename")
    return if (file.length() <= SIMPLE_LIMIT) simpleUpload(token, file.readBytes(), path)
    else {
        val source = ChunkSource.FileSource(file)
        try { chunkedUpload(token, source, path) } finally { source.close() }
    }
}

private suspend fun OneDriveApi.simpleUpload(token: String, data: ByteArray, encodedPath: String): String? {
    val body = data.toRequestBody("application/octet-stream".toMediaType())
    val req = Request.Builder().url("$GRAPH/me/drive/root:/$encodedPath:/content")
        .header("Authorization", "Bearer $token").put(body).build()
    return client.newCall(req).await().use {
        val rb = it.body?.string()
        logToFile("[OD] upload resp=${it.code} ${rb?.take(200)}")
        if (it.code in 200..201 && rb != null) JSONObject(rb).optString("id", "").ifEmpty { null }
        else null
    }
}

private suspend fun OneDriveApi.chunkedUpload(token: String, source: ChunkSource, encodedPath: String): String? {
    val sessionBody = JSONObject().apply {
        put("item", JSONObject().apply { put("@microsoft.graph.conflictBehavior", "replace") })
    }.toString().toRequestBody("application/json".toMediaType())
    val sessionReq = Request.Builder().url("$GRAPH/me/drive/root:/$encodedPath:/createUploadSession")
        .header("Authorization", "Bearer $token").post(sessionBody).build()
    val uploadUrl = client.newCall(sessionReq).await().use {
        if (!it.isSuccessful) return null
        JSONObject(it.body?.string() ?: "{}").optString("uploadUrl", "").ifEmpty { return null }
    }

    val total = source.totalSize
    var offset = 0L
    while (offset < total) {
        val end = minOf(offset + CHUNK_SIZE, total)
        val sz = (end - offset).toInt()
        val chunk = source.readChunk(offset, sz)
        for (retry in 1..MAX_RETRIES) {
            val req = Request.Builder().url(uploadUrl)
                .header("Content-Length", sz.toString())
                .header("Content-Range", "bytes $offset-${end - 1}/$total")
                .put(chunk.toRequestBody("application/octet-stream".toMediaType())).build()
            val resp = client.newCall(req).await()
            val code = resp.code
            val rb = resp.body?.string()
            when {
                code in listOf(200, 201) ->
                    return JSONObject(rb ?: "{}").optString("id", "").ifEmpty { null }
                code == 202 -> { offset = end; break }
                (code in 500..599 || code == 0) && retry < MAX_RETRIES -> {
                    logToFile("[OD] chunk retry $retry/$MAX_RETRIES code=$code")
                    delay(2000L * retry)
                }
                else -> { logToFile("[OD] chunk failed code=$code"); return null }
            }
        }
    }
    return null
}
