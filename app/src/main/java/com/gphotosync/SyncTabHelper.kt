package com.gphotosync

import android.content.Intent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SyncTabHelper(
    private val activity: AppCompatActivity,
    private val syncView: View,
    private val progressDb: SyncProgressStore
) {
    var isSyncing = false
    private var liveLogLines = mutableListOf<String>()
    private var historyEntries = listOf<String>()
    private var selectedSessionId: String? = null

    fun setup() {
        syncView.findViewById<Button>(R.id.btnSync).setOnClickListener {
            if (isSyncing) {
                val stopIntent = Intent(activity, SyncForegroundService::class.java)
                stopIntent.action = SyncForegroundService.ACTION_STOP
                activity.startForegroundService(stopIntent)
                isSyncing = false
                syncView.findViewById<Button>(R.id.btnSync).text = "동기화 시작"
                syncView.findViewById<Button>(R.id.btnSync).setBackgroundColor(0xFF2E7D32.toInt())
            } else {
                if (!TokenManager.isGoogleAuthed() || !TokenManager.isMicrosoftAuthed()) {
                    Toast.makeText(activity, "먼저 인증 탭에서 로그인하세요", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                startSync()
            }
        }

        syncView.findViewById<Button>(R.id.btnRetryFailed).setOnClickListener { retryFailed() }

        syncView.findViewById<Button>(R.id.btnShowDetail)?.setOnClickListener {
            syncView.findViewById<View>(R.id.cardDetails).visibility = View.VISIBLE
            refreshDetailLists()
        }
        syncView.findViewById<Button>(R.id.btnHideDetail)?.setOnClickListener {
            syncView.findViewById<View>(R.id.cardDetails).visibility = View.GONE
        }
        syncView.findViewById<Button>(R.id.btnTabSuccess)?.setOnClickListener {
            syncView.findViewById<View>(R.id.layoutSuccess).visibility = View.VISIBLE
            syncView.findViewById<View>(R.id.layoutFailed).visibility = View.GONE
            syncView.findViewById<Button>(R.id.btnTabSuccess).setBackgroundColor(0xFF4CAF50.toInt())
            syncView.findViewById<Button>(R.id.btnTabFailed).setBackgroundColor(0xFF9E9E9E.toInt())
            refreshDetailLists()
        }
        syncView.findViewById<Button>(R.id.btnTabFailed)?.setOnClickListener {
            syncView.findViewById<View>(R.id.layoutSuccess).visibility = View.GONE
            syncView.findViewById<View>(R.id.layoutFailed).visibility = View.VISIBLE
            syncView.findViewById<Button>(R.id.btnTabSuccess).setBackgroundColor(0xFF9E9E9E.toInt())
            syncView.findViewById<Button>(R.id.btnTabFailed).setBackgroundColor(0xFFF44336.toInt())
            refreshDetailLists()
        }

        loadHistorySummary()
        updateRetryButton()
    }

    private fun startSync() {
        isSyncing = true
        liveLogLines.clear()
        syncView.findViewById<TextView>(R.id.tvLiveLog).text = ""
        syncView.findViewById<Button>(R.id.btnSync).text = "동기화 중단"
        syncView.findViewById<Button>(R.id.btnSync).setBackgroundColor(0xFFF44336.toInt())
        syncView.findViewById<ProgressBar>(R.id.progressBar).visibility = View.VISIBLE
        syncView.findViewById<TextView>(R.id.tvProgressDetail).visibility = View.VISIBLE
        syncView.findViewById<View>(R.id.layoutStats).visibility = View.VISIBLE
        syncView.findViewById<ProgressBar>(R.id.progressBar).isIndeterminate = true

        val intent = Intent(activity, SyncForegroundService::class.java)
        intent.action = SyncForegroundService.ACTION_START
        activity.startForegroundService(intent)
    }

    private fun retryFailed() {
        val failedRecords = progressDb.getFailedRecords()
        if (failedRecords.isEmpty()) {
            Toast.makeText(activity, "재시도할 항목이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        isSyncing = true
        liveLogLines.clear()
        syncView.findViewById<TextView>(R.id.tvLiveLog).text = ""
        syncView.findViewById<Button>(R.id.btnSync).text = "동기화 중단"
        syncView.findViewById<Button>(R.id.btnSync).setBackgroundColor(0xFFF44336.toInt())
        syncView.findViewById<ProgressBar>(R.id.progressBar).visibility = View.VISIBLE
        syncView.findViewById<ProgressBar>(R.id.progressBar).isIndeterminate = true
        syncView.findViewById<TextView>(R.id.tvProgressDetail).visibility = View.VISIBLE
        syncView.findViewById<View>(R.id.layoutStats).visibility = View.VISIBLE

        SyncForegroundService.retryItems = failedRecords
        val intent = Intent(activity, SyncForegroundService::class.java)
        intent.action = SyncForegroundService.ACTION_RETRY
        activity.startForegroundService(intent)
    }

    fun appendLiveLog(line: String) {
        liveLogLines.add(line)
        if (liveLogLines.size > 50) liveLogLines.removeAt(0)
        val tv = syncView.findViewById<TextView>(R.id.tvLiveLog)
        tv.text = liveLogLines.joinToString("\n")
        val sv = syncView.findViewById<ScrollView>(R.id.scrollLiveLog)
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }
    }

    fun updateProgress(progress: SyncProgress) {
        val pb = syncView.findViewById<ProgressBar>(R.id.progressBar)
        val tvDetail = syncView.findViewById<TextView>(R.id.tvProgressDetail)
        pb.visibility = View.VISIBLE
        tvDetail.visibility = View.VISIBLE
        syncView.findViewById<View>(R.id.layoutStats).visibility = View.VISIBLE

        if (progress.total > 0) {
            pb.isIndeterminate = false
            pb.max = progress.total
            pb.progress = progress.done
            val pct = progress.done * 100 / progress.total
            val totalMB = String.format("%.1f", progress.totalBytes / 1024.0 / 1024.0)
            val doneMB = String.format("%.1f", progress.doneBytes / 1024.0 / 1024.0)
            tvDetail.text = "${progress.done}/${progress.total} ($pct%) | ${doneMB}MB / ${totalMB}MB"
        }

        syncView.findViewById<TextView>(R.id.tvTotal).text = "전체: ${progress.total}"
        val successCount = progress.done - progress.errors - progress.skipped
        syncView.findViewById<TextView>(R.id.tvDone).text = "완료: $successCount"
        syncView.findViewById<TextView>(R.id.tvSkipped).text = "스킵: ${progress.skipped}"
        syncView.findViewById<TextView>(R.id.tvErrors).text = "실패: ${progress.errors}"

        if (progress.finished) {
            isSyncing = false
            syncView.findViewById<Button>(R.id.btnSync).text = "동기화 시작"
            syncView.findViewById<Button>(R.id.btnSync).setBackgroundColor(0xFF2E7D32.toInt())
            if (progress.errorMessage != null) {
                syncView.findViewById<TextView>(R.id.tvStatus).text = "오류: ${progress.errorMessage}"
            } else {
                syncView.findViewById<TextView>(R.id.tvStatus).text = "동기화 완료! 성공:$successCount 스킵:${progress.skipped} 실패:${progress.errors}"
            }
            saveHistorySummary(progress)
            loadHistorySummary()
            refreshDetailLists()
            updateRetryButton()
        } else {
            syncView.findViewById<TextView>(R.id.tvStatus).text = "동기화 중..."
        }
    }

    private fun updateRetryButton() {
        val btn = syncView.findViewById<Button>(R.id.btnRetryFailed)
        val failedCount = progressDb.getFailedRecords().size
        if (failedCount > 0) {
            btn.visibility = View.VISIBLE
            btn.text = "🔄 실패 항목 재시도 (${failedCount}건)"
        } else {
            btn.visibility = View.GONE
        }
    }

    private fun refreshDetailLists() {
        val sid = selectedSessionId
        val successRecords = if (sid != null) progressDb.getSuccessRecordsBySession(sid) else progressDb.getSuccessRecords()
        val failedRecords = if (sid != null) progressDb.getFailedRecordsBySession(sid) else progressDb.getFailedRecords()
        val dateFormat = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())

        syncView.findViewById<TextView>(R.id.tvSuccessCount).text = "완료: ${successRecords.size}건"
        syncView.findViewById<TextView>(R.id.tvSuccessList).text = if (successRecords.isEmpty()) "내역 없음"
        else successRecords.take(200).joinToString("\n") { r ->
            val time = dateFormat.format(Date(r.timestamp))
            val size = if (r.fileSize > 0) formatSize(r.fileSize) else ""
            "[$time] ${r.filename} $size"
        }

        syncView.findViewById<TextView>(R.id.tvFailedCount).text = "실패: ${failedRecords.size}건"
        syncView.findViewById<TextView>(R.id.tvFailedList).text = if (failedRecords.isEmpty()) "내역 없음"
        else failedRecords.joinToString("\n") { r ->
            val time = dateFormat.format(Date(r.timestamp))
            "[$time] ${r.filename}\n  > ${r.error}"
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format("%.1fMB", bytes / 1024.0 / 1024.0)
            bytes >= 1024 -> String.format("%.0fKB", bytes / 1024.0)
            else -> "${bytes}B"
        }
    }

    private fun saveHistorySummary(progress: SyncProgress) {
        val prefs = activity.getSharedPreferences("sync_history", AppCompatActivity.MODE_PRIVATE)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val now = fmt.format(Date())
        val success = progress.done - progress.errors - progress.skipped
        val totalMB = String.format("%.1f", progress.totalBytes / 1024.0 / 1024.0)
        val sessionId = if (progress.sessionId.isNotEmpty()) progress.sessionId else now
        val entry = "$sessionId | 성공:$success 스킵:${progress.skipped} 실패:${progress.errors} (전체:${progress.total}, ${totalMB}MB)"
        val history = prefs.getStringSet("entries", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        history.add(entry)
        prefs.edit().putStringSet("entries", history).putString("latest", entry).apply()
    }

    fun setSyncingUI() {
        isSyncing = true
        syncView.findViewById<Button>(R.id.btnSync).text = "동기화 중단"
        syncView.findViewById<Button>(R.id.btnSync).setBackgroundColor(0xFFF44336.toInt())
        syncView.findViewById<ProgressBar>(R.id.progressBar).visibility = View.VISIBLE
        syncView.findViewById<ProgressBar>(R.id.progressBar).isIndeterminate = true
        syncView.findViewById<TextView>(R.id.tvProgressDetail).visibility = View.VISIBLE
        syncView.findViewById<View>(R.id.layoutStats).visibility = View.VISIBLE
    }

    fun setIdleUI() {
        isSyncing = false
        syncView.findViewById<Button>(R.id.btnSync).text = "동기화 시작"
        syncView.findViewById<Button>(R.id.btnSync).setBackgroundColor(0xFF2E7D32.toInt())
        syncView.findViewById<ProgressBar>(R.id.progressBar).visibility = View.GONE
        syncView.findViewById<TextView>(R.id.tvProgressDetail).visibility = View.GONE
    }

    fun loadHistorySummary() {
        val prefs = activity.getSharedPreferences("sync_history", AppCompatActivity.MODE_PRIVATE)
        val history = prefs.getStringSet("entries", emptySet()) ?: emptySet()
        val card = syncView.findViewById<View>(R.id.cardHistory)
        val lv = syncView.findViewById<ListView>(R.id.lvHistory)

        if (history.isNotEmpty()) {
            card.visibility = View.VISIBLE
            historyEntries = history.sortedDescending().take(20)
            val adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_activated_1, historyEntries)
            lv.adapter = adapter
            lv.choiceMode = ListView.CHOICE_MODE_SINGLE
            lv.setOnItemClickListener { _, _, position, _ ->
                lv.setItemChecked(position, true)
                val selected = historyEntries[position]
                selectedSessionId = selected.substringBefore(" |").trim()
                refreshDetailLists()
            }
        }

        val successCount = progressDb.getSuccessRecords().size
        val failedCount = progressDb.getFailedRecords().size
        if (successCount > 0 || failedCount > 0) {
            card.visibility = View.VISIBLE
        }
    }
}
