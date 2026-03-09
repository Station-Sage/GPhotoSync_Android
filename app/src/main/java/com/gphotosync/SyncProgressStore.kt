package com.gphotosync

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class SyncRecord(
    val id: String,
    val filename: String,
    val status: String, // "success" or "failed"
    val error: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val fileSize: Long = 0
)

class SyncProgressStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("sync_progress", Context.MODE_PRIVATE)

    fun loadSyncedIds(): Set<String> {
        return prefs.getStringSet("synced_ids", emptySet()) ?: emptySet()
    }

    fun saveSyncedId(id: String) {
        val current = loadSyncedIds().toMutableSet()
        current.add(id)
        prefs.edit().putStringSet("synced_ids", current).apply()
    }

    fun removeSyncedId(id: String) {
        val current = loadSyncedIds().toMutableSet()
        current.remove(id)
        prefs.edit().putStringSet("synced_ids", current).apply()
    }

    // 완료 내역
    fun addSuccessRecord(record: SyncRecord) {
        val list = getSuccessRecords().toMutableList()
        list.add(0, record)
        if (list.size > 500) list.subList(500, list.size).clear()
        prefs.edit().putString("success_records", recordsToJson(list)).apply()
    }

    fun getSuccessRecords(): List<SyncRecord> {
        val json = prefs.getString("success_records", "[]") ?: "[]"
        return jsonToRecords(json)
    }

    // 실패 내역
    fun addFailedRecord(record: SyncRecord) {
        val list = getFailedRecords().toMutableList()
        list.removeAll { it.id == record.id }
        list.add(0, record)
        prefs.edit().putString("failed_records", recordsToJson(list)).apply()
    }

    fun getFailedRecords(): List<SyncRecord> {
        val json = prefs.getString("failed_records", "[]") ?: "[]"
        return jsonToRecords(json)
    }

    fun removeFailedRecord(id: String) {
        val list = getFailedRecords().toMutableList()
        list.removeAll { it.id == id }
        prefs.edit().putString("failed_records", recordsToJson(list)).apply()
    }

    fun clearFailedRecords() {
        prefs.edit().putString("failed_records", "[]").apply()
    }

    // 파일 크기 저장 (중복 체크용)
    fun saveSyncedFileSize(id: String, size: Long) {
        prefs.edit().putLong("fsize_$id", size).apply()
    }

    fun getSyncedFileSize(id: String): Long {
        return prefs.getLong("fsize_$id", -1)
    }

    fun getTotalCount(): Int = prefs.getInt("total_count", 0)
    fun setTotalCount(n: Int) = prefs.edit().putInt("total_count", n).apply()

    fun getDoneCount(): Int = prefs.getInt("done_count", 0)
    fun setDoneCount(n: Int) = prefs.edit().putInt("done_count", n).apply()

    fun reset() = prefs.edit().clear().apply()

    private fun recordsToJson(records: List<SyncRecord>): String {
        val arr = JSONArray()
        for (r in records) {
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("filename", r.filename)
                put("status", r.status)
                put("error", r.error)
                put("timestamp", r.timestamp)
                put("fileSize", r.fileSize)
            })
        }
        return arr.toString()
    }

    private fun jsonToRecords(json: String): List<SyncRecord> {
        val list = mutableListOf<SyncRecord>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(SyncRecord(
                    id = obj.getString("id"),
                    filename = obj.optString("filename", ""),
                    status = obj.optString("status", ""),
                    error = obj.optString("error", ""),
                    timestamp = obj.optLong("timestamp", 0),
                    fileSize = obj.optLong("fileSize", 0)
                ))
            }
        } catch (_: Exception) {}
        return list
    }
}
