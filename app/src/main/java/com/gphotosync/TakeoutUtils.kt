package com.gphotosync

import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream

// === 데이터 클래스 ===
data class TakeoutProgress(
    val done: Int, val total: Int, val errors: Int, val finished: Boolean,
    val errorMessage: String?, val doneBytes: Long = 0L, val skipped: Int = 0
)

data class UploadItem(
    val zipName: String, val fn: String, val fp: String,
    val data: ByteArray, val fileSize: Long, val tmpFile: java.io.File?
)

// === 스트림 유틸 ===
class CountingInputStream(private val wrapped: InputStream) : InputStream() {
    var bytesRead: Long = 0L
        private set
    override fun read(): Int {
        val b = wrapped.read()
        if (b != -1) bytesRead++
        return b
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = wrapped.read(b, off, len)
        if (n > 0) bytesRead += n
        return n
    }
    override fun close() = wrapped.close()
    override fun available() = wrapped.available()
}

private val drainBuf = ByteArray(262144) // 256KB
fun drainZipEntry(zis: ZipArchiveInputStream) {
    try { while (zis.read(drainBuf) != -1) { } } catch (_: Exception) {}
}

fun readJsonSafe(zis: ZipArchiveInputStream): String? {
    val maxSize = 65536
    val baos = ByteArrayOutputStream(8192)
    val buf = ByteArray(32768)
    var total = 0
    var n = zis.read(buf)
    while (n != -1) {
        total += n
        if (total <= maxSize) baos.write(buf, 0, n)
        n = zis.read(buf)
    }
    return if (total <= maxSize) baos.toString(Charsets.UTF_8.name()) else null
}

// === 미디어 판별 ===
private val imageExt = setOf("jpg","jpeg","png","gif","bmp","webp","heic","heif","tiff","tif","raw","cr2","nef","arw","dng","svg")
private val videoExt = setOf("mp4","mov","avi","mkv","wmv","flv","webm","m4v","3gp","3g2","mts","m2ts")
fun isMediaFile(name: String) = name.substringAfterLast('.', "").lowercase().let { it in imageExt || it in videoExt }

// === JSON 메타데이터 파싱 ===
fun parseJsonMeta(json: String): Pair<String, String>? {
    try {
        val obj = org.json.JSONObject(json)
        val title = obj.optString("title", "")
        var ts = 0L
        if (obj.has("photoTakenTime")) ts = obj.getJSONObject("photoTakenTime").optString("timestamp","0").toLongOrNull() ?: 0L
        if (ts == 0L && obj.has("creationTime")) ts = obj.getJSONObject("creationTime").optString("timestamp","0").toLongOrNull() ?: 0L
        if (title.isNotEmpty() && ts > 0) {
            val d = java.util.Date(ts * 1000)
            val y = java.text.SimpleDateFormat("yyyy", java.util.Locale.getDefault()).format(d)
            return Pair(title, y)
        }
    } catch (_: Exception) {}
    return null
}

// === 앨범명 추출 ===
// "Takeout/Google Photos/Photos from 2024/file.jpg" → null (날짜 폴더)
// "Takeout/Google Photos/여행 2023/file.jpg" → "여행 2023" (앨범)
private val dateFolderPatterns = listOf(
    Regex("""Photos from \d{4}"""),
    Regex("""^\d{4}년\s*\d{1,2}월\s*\d{1,2}일$"""),
    Regex("""^\d{4}-\d{2}-\d{2}$""")
)

fun extractAlbumName(zipPath: String): String? {
    val parts = zipPath.split("/")
    if (parts.size < 3) return null
    val gpIdx = parts.indexOfFirst { it == "Google Photos" || it == "Google 포토" || it.startsWith("Google") }
    if (gpIdx < 0 || gpIdx + 1 >= parts.size - 1) return null
    val folderName = parts[gpIdx + 1]
    for (p in dateFolderPatterns) { if (p.matches(folderName)) return null }
    if (folderName.isBlank() || folderName.length < 2) return null
    return folderName
}

// === 날짜 유틸 ===
fun dateFromName(filename: String, path: String): String? {
    val p = Regex("""((?:19|20)\d{2})[-_]?(\d{2})[-_]?(\d{2})""")
    val m = p.find(filename) ?: p.find(path) ?: return null
    return "${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]}"
}

fun inRange(fd: String?, s: String?, e: String?): Boolean {
    if (s == null && e == null) return true
    if (fd == null) return false
    if (s != null && fd < s) return false
    if (e != null && fd > e) return false
    return true
}

fun yearOnly(filename: String, path: String, jsonDateMap: Map<String, String>): String {
    jsonDateMap[filename]?.let { return it.substringBefore("/") }
    val yr = Regex("""((?:19|20)\d{2})""")
    (yr.find(filename) ?: yr.find(path))?.let { return it.groupValues[1] }
    return "unknown"
}
