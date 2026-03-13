package com.gphotosync

import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Takeout 탭의 상태 관리 및 UI 업데이트 담당
 * TakeoutTabHelper에서 분리 (WORKFLOW.md 300줄 제한)
 */
class TakeoutTabState(
    private val activity: AppCompatActivity,
    private val takeoutView: View,
    private val helper: TakeoutTabHelper
) {
    fun setupCallbacks() {
        if (helper.isTakeoutAnalyzing) {
            TakeoutUploadService.progressCallback = { progress ->
                activity.runOnUiThread {
                    helper.lastTakeoutProgress = progress
                    if (progress.total > 0 && !progress.finished) {
                        val pb = takeoutView.findViewById<ProgressBar>(R.id.takeoutProgressBar)
                        pb.visibility = View.VISIBLE
                        pb.isIndeterminate = false
                        pb.max = progress.total
                        pb.progress = progress.done
                        val pct = if (progress.total > 0) progress.done * 100 / progress.total else 0
                        val readMB = String.format("%.0f", progress.doneBytes / 1024.0 / 1024.0)
                        val analyzeText = "분석 중: $pct% (${readMB}MB)"
                        takeoutView.findViewById<TextView>(R.id.tvTakeoutProgress).visibility = View.VISIBLE
                        takeoutView.findViewById<TextView>(R.id.tvTakeoutProgress).text = analyzeText
                        helper.lastAnalyzeStatusText = analyzeText
                    }
                }
            }
        } else {
            TakeoutUploadService.progressCallback = { progress ->
                activity.runOnUiThread { updateTakeoutProgress(progress) }
            }
        }
        TakeoutUploadService.logCallback = { line ->
            activity.runOnUiThread { helper.appendTakeoutLog(line) }
        }
        TakeoutUploadService.refreshLogCallback = {
            activity.runOnUiThread {
                val tv = takeoutView.findViewById<TextView>(R.id.tvTakeoutLog)
                synchronized(TakeoutUploadService.logBuffer) {
                    helper.takeoutLogLines.clear()
                    helper.takeoutLogLines.addAll(TakeoutUploadService.logBuffer)
                    tv.text = helper.takeoutLogLines.joinToString("\n")
                }
                val sv = takeoutView.findViewById<ScrollView>(R.id.scrollTakeoutLog)
                sv.post { sv.fullScroll(View.FOCUS_DOWN) }
            }
        }
        // Activity 복귀 시 이전 로그 복원
        helper.takeoutLogLines.clear()
        takeoutView.findViewById<TextView>(R.id.tvTakeoutLog).text = ""
        synchronized(TakeoutUploadService.logBuffer) {
            TakeoutUploadService.logBuffer.forEach { line -> activity.runOnUiThread { helper.appendTakeoutLog(line) } }
        }
        TakeoutUploadService.authExpiredCallback = {
            activity.runOnUiThread {
                androidx.appcompat.app.AlertDialog.Builder(activity)
                    .setTitle("⚠ MS 인증 만료")
                    .setMessage("Microsoft 인증이 만료되었습니다.\n인증 탭에서 재로그인 후 다시 시도하세요.")
                    .setPositiveButton("인증 탭으로 이동") { _, _ ->
                        activity.findViewById<com.google.android.material.tabs.TabLayout>(R.id.tabLayout)?.getTabAt(1)?.select()
                    }
                    .setNegativeButton("닫기", null)
                    .show()
            }
        }
    }

    fun restorePreviousSession() {
        if (TakeoutUploadService.isRunning) {
            helper.isTakeoutUploading = true
            setupCallbacks()
            val p = TakeoutUploadService.currentProgress
            if (p != null) {
                activity.runOnUiThread { applyUploadingUI(p) }
            }
            return
        }
        val prefs = activity.getSharedPreferences("takeout_session", AppCompatActivity.MODE_PRIVATE)
        val savedUri = prefs.getString("zip_uri", null)
        if (savedUri != null && helper.selectedZipUri == null) {
            try {
                val uri = Uri.parse(savedUri)
                activity.contentResolver.openFileDescriptor(uri, "r")?.close()
                helper.selectedZipUri = uri

                val savedMedia = activity.getSharedPreferences("takeout_media_list", AppCompatActivity.MODE_PRIVATE)
                    .getStringSet("names", emptySet()) ?: emptySet()
                if (savedMedia.isNotEmpty()) {
                    val savedSize = activity.getSharedPreferences("takeout_analyze", AppCompatActivity.MODE_PRIVATE).getLong("ts", 0L)
                    val sizeMB = String.format("%.1f", savedSize / 1024.0 / 1024.0)
                    val sizeText = if (savedSize > 0) " (약 ${sizeMB}MB)" else ""
                    helper.lastZipInfoText = "미디어 파일: ${savedMedia.size}개${sizeText} (이전 분석 결과)"
                    takeoutView.findViewById<TextView>(R.id.tvZipInfo).text = helper.lastZipInfoText
                    takeoutView.findViewById<Button>(R.id.btnStartTakeout).isEnabled = true
                    takeoutView.findViewById<Button>(R.id.btnStartTakeout).visibility = View.VISIBLE
                    takeoutView.findViewById<TextView>(R.id.tvTakeoutStatus).text = "이전 분석 결과 로드 완료. 업로드 가능합니다."
                    helper.appendTakeoutLog("이전 세션 복원: 미디어 ${savedMedia.size}개${sizeText}")

                    val hasResumable = activity.getSharedPreferences("takeout_progress", AppCompatActivity.MODE_PRIVATE)
                        .getStringSet("uf", emptySet())?.isNotEmpty() == true
                    if (hasResumable) {
                        takeoutView.findViewById<Button>(R.id.btnResumeTakeout).visibility = View.VISIBLE
                        helper.appendTakeoutLog("이전 업로드 이어하기 가능")
                    }
                    val albumCount = activity.getSharedPreferences("takeout_album_map", AppCompatActivity.MODE_PRIVATE).getInt("c", 0)
                    if (albumCount > 0 && hasResumable) {
                        takeoutView.findViewById<Button>(R.id.btnOrganizeAlbums)?.visibility = View.VISIBLE
                    }
                    if (hasResumable) {
                        takeoutView.findViewById<Button>(R.id.btnMigrateFolder)?.visibility = View.VISIBLE
                    }
                    takeoutView.findViewById<Button>(R.id.btnReanalyze)?.visibility = View.VISIBLE
                } else {
                    takeoutView.findViewById<TextView>(R.id.tvZipInfo).text = "이전 ZIP 파일 선택됨 (분석 필요)"
                    helper.appendTakeoutLog("이전 ZIP 파일 복원됨. 분석이 필요합니다.")
                }
            } catch (e: Exception) {
                prefs.edit().remove("zip_uri").apply()
            }
        }
    }

    fun restoreState() {
        // 공통: 로그, ZIP 정보, 날짜 필터 복원
        if (helper.takeoutLogLines.isNotEmpty()) {
            takeoutView.findViewById<TextView>(R.id.tvTakeoutLog).text = helper.takeoutLogLines.joinToString("\n")
            val sv = takeoutView.findViewById<ScrollView>(R.id.scrollTakeoutLog)
            sv.post { sv.fullScroll(View.FOCUS_DOWN) }
        }
        if (helper.selectedZipUri != null) takeoutView.findViewById<TextView>(R.id.tvZipInfo)?.text = helper.lastZipInfoText
        if (helper.filterStartDate != null) takeoutView.findViewById<Button>(R.id.btnStartDate)?.text = helper.filterStartDate
        if (helper.filterEndDate != null) takeoutView.findViewById<Button>(R.id.btnEndDate)?.text = helper.filterEndDate
        if (helper.filterStartDate != null || helper.filterEndDate != null) {
            takeoutView.findViewById<TextView>(R.id.tvDateRange)?.text = "${helper.filterStartDate ?: "처음"} ~ ${helper.filterEndDate ?: "끝"}"
        }

        // 상태별 UI 분기
        val isUploading = TakeoutUploadService.isRunning || helper.isTakeoutUploading || (TakeoutUploadService.currentProgress != null && TakeoutUploadService.currentProgress!!.finished != true)
        if (helper.isTakeoutAnalyzing) {
            // === 분석 중 ===
            val pb = takeoutView.findViewById<ProgressBar>(R.id.takeoutProgressBar)
            pb.visibility = View.VISIBLE
            takeoutView.findViewById<TextView>(R.id.tvTakeoutProgress).visibility = View.VISIBLE
            takeoutView.findViewById<TextView>(R.id.tvTakeoutProgress).text = helper.lastAnalyzeStatusText
            takeoutView.findViewById<TextView>(R.id.tvTakeoutStatus).visibility = View.VISIBLE
            takeoutView.findViewById<TextView>(R.id.tvTakeoutStatus).text = "백그라운드에서 ZIP 분석 중..."
            takeoutView.findViewById<Button>(R.id.btnStopAnalyze).visibility = View.VISIBLE
            takeoutView.findViewById<Button>(R.id.btnResumeAnalyze).visibility = View.GONE
            takeoutView.findViewById<Button>(R.id.btnStartTakeout).isEnabled = false
            takeoutView.findViewById<Button>(R.id.btnStopTakeout).visibility = View.GONE
            takeoutView.findViewById<Button>(R.id.btnResumeTakeout)?.visibility = View.GONE
            takeoutView.findViewById<Button>(R.id.btnOrganizeAlbums)?.visibility = View.GONE
            takeoutView.findViewById<Button>(R.id.btnMigrateFolder)?.visibility = View.GONE
            takeoutView.findViewById<Button>(R.id.btnReanalyze)?.visibility = View.GONE
            val p = helper.lastTakeoutProgress
            if (p != null && p.total > 0) { pb.isIndeterminate = false; pb.max = p.total; pb.progress = p.done }
        } else if (isUploading) {
            // === 업로드 중 ===
            helper.isTakeoutUploading = true
            setupCallbacks()
            applyUploadingUI(TakeoutUploadService.currentProgress ?: helper.lastTakeoutProgress)
        } else if (helper.selectedZipUri != null) {
            // === 대기 중 (ZIP 선택됨) ===
            applyIdleUI()
        }
    }

    private fun updateTakeoutProgress(progress: TakeoutProgress) {
        helper.lastTakeoutProgress = progress
        if (progress.finished) {
            helper.isTakeoutUploading = false
            applyFinishedUI(progress)
        } else {
            applyUploadingUI(progress)
        }
    }

    fun applyUploadingUI(p: TakeoutProgress?) {
        val pb = takeoutView.findViewById<ProgressBar>(R.id.takeoutProgressBar)
        pb.visibility = View.VISIBLE
        if (p != null && p.total > 0) {
            pb.isIndeterminate = false; pb.max = p.total; pb.progress = p.done
            val pct = p.done * 100 / p.total
            val doneMB = String.format("%.1f", p.doneBytes / 1024.0 / 1024.0)
            val elapsed = if (TakeoutUploadService.actualUploadStartTime > 0) (System.currentTimeMillis() - TakeoutUploadService.actualUploadStartTime) / 1000.0 else 0.0
            val speedText = if (elapsed > 1 && p.doneBytes > 0) String.format(" %.1fMB/s", p.doneBytes / 1048576.0 / elapsed) else ""
            val skipText = if (p.skipped > 0) " 스킵:${p.skipped}" else ""
            takeoutView.findViewById<TextView>(R.id.tvTakeoutProgress).visibility = View.VISIBLE
            takeoutView.findViewById<TextView>(R.id.tvTakeoutProgress).text = "${p.done}/${p.total} ($pct%) | ${doneMB}MB$speedText$skipText"
        }
        takeoutView.findViewById<TextView>(R.id.tvTakeoutStatus).visibility = View.VISIBLE
        takeoutView.findViewById<TextView>(R.id.tvTakeoutStatus).text = "업로드 중..."
        takeoutView.findViewById<Button>(R.id.btnStopTakeout).visibility = View.VISIBLE
        takeoutView.findViewById<Button>(R.id.btnStartTakeout).visibility = View.GONE
        takeoutView.findViewById<Button>(R.id.btnResumeTakeout)?.visibility = View.GONE
        takeoutView.findViewById<Button>(R.id.btnOrganizeAlbums)?.visibility = View.GONE
        takeoutView.findViewById<Button>(R.id.btnMigrateFolder)?.visibility = View.GONE
        takeoutView.findViewById<Button>(R.id.btnReanalyze)?.visibility = View.GONE
    }

    fun applyFinishedUI(progress: TakeoutProgress) {
        takeoutView.findViewById<ProgressBar>(R.id.takeoutProgressBar).visibility = View.GONE
        takeoutView.findViewById<Button>(R.id.btnStopTakeout).visibility = View.GONE
        takeoutView.findViewById<Button>(R.id.btnStartTakeout).isEnabled = true
        takeoutView.findViewById<Button>(R.id.btnStartTakeout).visibility = View.VISIBLE
        takeoutView.findViewById<Button>(R.id.btnStartTakeout).text = "🚀 OneDrive에 업로드"
        val hasResumable = activity.getSharedPreferences("takeout_progress", AppCompatActivity.MODE_PRIVATE)
            .getStringSet("uf", emptySet())?.isNotEmpty() == true
        takeoutView.findViewById<Button>(R.id.btnResumeTakeout).visibility =
            if (hasResumable && progress.errorMessage?.contains("중단") == true) View.VISIBLE else View.GONE
        val success = progress.done - progress.errors - progress.skipped
        val tvStatus = takeoutView.findViewById<TextView>(R.id.tvTakeoutStatus)
        if (progress.errorMessage != null) {
            val s = "오류: ${progress.errorMessage}"; tvStatus.text = s; helper.lastTakeoutStatusText = s
        } else {
            val s = "완료! 성공:${success} 스킵:${progress.skipped} 실패:${progress.errors}"; tvStatus.text = s; helper.lastTakeoutStatusText = s
        }
        val albumCount = activity.getSharedPreferences("takeout_album_map", AppCompatActivity.MODE_PRIVATE).getInt("c", 0)
        if (albumCount > 0) takeoutView.findViewById<Button>(R.id.btnOrganizeAlbums)?.visibility = View.VISIBLE
        takeoutView.findViewById<Button>(R.id.btnMigrateFolder)?.visibility = View.VISIBLE
        takeoutView.findViewById<Button>(R.id.btnReanalyze)?.visibility = View.VISIBLE
    }

    fun applyIdleUI() {
        takeoutView.findViewById<ProgressBar>(R.id.takeoutProgressBar).visibility = View.GONE
        takeoutView.findViewById<TextView>(R.id.tvTakeoutStatus).text = helper.lastTakeoutStatusText
        takeoutView.findViewById<Button>(R.id.btnStartTakeout).visibility = View.VISIBLE
        takeoutView.findViewById<Button>(R.id.btnStartTakeout).isEnabled = true
        takeoutView.findViewById<Button>(R.id.btnStopTakeout).visibility = View.GONE
        val hasResumable = activity.getSharedPreferences("takeout_progress", 0)
            .getStringSet("uf", emptySet())?.isNotEmpty() == true
        takeoutView.findViewById<Button>(R.id.btnResumeTakeout)?.visibility = if (hasResumable) View.VISIBLE else View.GONE
        val albumCount = activity.getSharedPreferences("takeout_album_map", AppCompatActivity.MODE_PRIVATE).getInt("c", 0)
        takeoutView.findViewById<Button>(R.id.btnOrganizeAlbums)?.visibility = if (albumCount > 0 && hasResumable) View.VISIBLE else View.GONE
        takeoutView.findViewById<Button>(R.id.btnMigrateFolder)?.visibility = if (hasResumable) View.VISIBLE else View.GONE
        takeoutView.findViewById<Button>(R.id.btnReanalyze)?.visibility = View.VISIBLE
    }
}
