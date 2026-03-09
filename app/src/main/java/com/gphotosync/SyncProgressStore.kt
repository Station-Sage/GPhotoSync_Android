package com.gphotosync

import android.content.Context
import android.content.SharedPreferences

/**
 * 동기화 진행 상황 저장소
 * SharedPreferences를 Set으로 사용 (완료된 Google Photos ID 추적)
 */
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

    fun getTotalCount(): Int = prefs.getInt("total_count", 0)
    fun setTotalCount(n: Int) = prefs.edit().putInt("total_count", n).apply()

    fun getDoneCount(): Int = prefs.getInt("done_count", 0)
    fun setDoneCount(n: Int) = prefs.edit().putInt("done_count", n).apply()

    fun reset() = prefs.edit().clear().apply()
}
