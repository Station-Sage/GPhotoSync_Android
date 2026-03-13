package com.gphotosync

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Calendar

/**
 * Takeout 탭의 UI 이벤트 처리 및 사용자 액션 담당
 * 상태 관리는 TakeoutTabState로 분리 (WORKFLOW.md 300줄 제한)
 */
class TakeoutTabHelper(
    private val activity: AppCompatActivity,
    private val takeoutView: View,
    private val zipFilePicker: androidx.activity.result.ActivityResultLauncher<String>
) {
    var selectedZipUri: Uri? = null
    var takeoutLogLines = mutableListOf<String>()
    var filterStartDate: String? = null
    var filterEndDate: String? = null
    var isTakeoutAnalyzing = false
    var isTakeoutUploading = false
    var lastTakeoutProgress: TakeoutProgress? = null
    var lastAnalyzeStatusText: String = ""
    var lastZipInfoText: String = "ZIP 파일을 선택하세요"
    var lastTakeoutStatusText: String = ""

    private val state = TakeoutTabState(activity, takeoutView, this)

    fun setupCallbacks() = state.setupCallbacks()
    fun restorePreviousSession() = state.restorePreviousSession()
    fun restoreState() = state.restoreState()

    fun setup() {
        restorePreviousSession()
        takeoutView.findViewById<Button>(R.id.btnOpenTakeout).setOnClickListener {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://takeout.google.com/settings/takeout/custom/photo")))
        }
        takeoutView.findViewById<Button>(R.id.btnSelectZip).setOnClickListener { zipFilePicker.launch("*/*") }

        takeoutView.findViewById<Button>(R.id.btnStartTakeout).setOnClickListener {
            val uri = selectedZipUri
            if (uri == null) { Toast.makeText(activity, "ZIP 파일을 먼저 선택하세요", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (!TokenManager.isMicrosoftAuthed()) { Toast.makeText(activity, "먼저 인증 탭에서 Microsoft 로그인하세요", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            startTakeoutUpload(uri)
        }

        takeoutView.findViewById<Button>(R.id.btnStopTakeout).setOnClickListener {
            val stopIntent = Intent(activity, TakeoutUploadService::class.java)
            stopIntent.action = TakeoutUploadService.ACTION_STOP
            activity.startForegroundService(stopIntent)
            takeoutView.findViewById<Button>(R.id.btnStopTakeout).isEnabled = false
        }

        takeoutView.findViewById<Button>(R.id.btnResumeTakeout).setOnClickListener {
            val uri = selectedZipUri
            if (uri == null) { Toast.makeText(activity, "ZIP 파일을 먼저 선택하세요", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (!TokenManager.isMicrosoftAuthed()) { Toast.makeText(activity, "먼저 인증 탭에서 Microsoft 로그인하세요", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            resumeTakeoutUpload(uri)
        }

        takeoutView.findViewById<Button>(R.id.btnStartDate).setOnClickListener { showDatePicker(true) }
        takeoutView.findViewById<Button>(R.id.btnEndDate).setOnClickListener { showDatePicker(false) }
        takeoutView.findViewById<Button>(R.id.btnClearDate).setOnClickListener {
            filterStartDate = null; filterEndDate = null
            takeoutView.findViewById<Button>(R.id.btnStartDate).text = "시작일 선택"
            takeoutView.findViewById<Button>(R.id.btnEndDate).text = "종료일 선택"
            takeoutView.findViewById<TextView>(R.id.tvDateRange).text = "전체 기간 (필터 없음)"
            activity.getSharedPreferences("takeout_media_list", AppCompatActivity.MODE_PRIVATE).edit().clear().apply()
            selectedZipUri?.let { onZipSelected(it) }
        }

        takeoutView.findViewById<Button>(R.id.btnOrganizeAlbums)?.setOnClickListener {
            appendTakeoutLog("앨범 폴더 정리 시작...")
            takeoutView.findViewById<Button>(R.id.btnOrganizeAlbums)?.isEnabled = false
            isTakeoutUploading = true; isTakeoutAnalyzing = false
            setupCallbacks()

            TakeoutUploadService.organizeCallback = fun(total: Int, copied: Int, errors: Int) {
                isTakeoutUploading = false
                takeoutView.findViewById<Button>(R.id.btnOrganizeAlbums)?.isEnabled = true
                if (total > 0) {
                    appendTakeoutLog("앨범 정리 완료: 복사 $copied, 실패 $errors (전체 $total)")
                    takeoutView.findViewById<TextView>(R.id.tvTakeoutStatus).text = "앨범 정리 완료! ${copied}개 복사"
                }
            }

            val intent = Intent(activity, TakeoutUploadService::class.java)
            intent.action = TakeoutUploadService.ACTION_ORGANIZE_ALBUMS
            activity.startForegroundService(intent)
        }

        takeoutView.findViewById<Button>(R.id.btnMigrateFolder)?.setOnClickListener {
            appendTakeoutLog("월 폴더 → 연도 폴더 정리 시작...")
            takeoutView.findViewById<Button>(R.id.btnMigrateFolder)?.isEnabled = false
            isTakeoutUploading = true; isTakeoutAnalyzing = false
            setupCallbacks()

            TakeoutUploadService.migrateCallback = fun(total: Int, moved: Int, errors: Int) {
                activity.runOnUiThread {
                    isTakeoutUploading = false
                    takeoutView.findViewById<Button>(R.id.btnMigrateFolder)?.isEnabled = true
                    if (total > 0) {
                        appendTakeoutLog("폴더 정리 완료: 이동 $moved, 실패 $errors (전체 $total)")
                        takeoutView.findViewById<TextView>(R.id.tvTakeoutStatus).text = "폴더 정리 완료! ${moved}개 이동"
                    } else {
                        appendTakeoutLog("정리할 월 폴더가 없습니다.")
                    }
                }
            }

            val intent = Intent(activity, TakeoutUploadService::class.java)
            intent.action = TakeoutUploadService.ACTION_MIGRATE
            activity.startForegroundService(intent)
        }

        takeoutView.findViewById<Button>(R.id.btnReanalyze)?.setOnClickListener {
            val uri = selectedZipUri ?: return@setOnClickListener
            activity.getSharedPreferences("takeout_media_list", AppCompatActivity.MODE_PRIVATE).edit().clear().apply()
            activity.getSharedPreferences("takeout_analyze", AppCompatActivity.MODE_PRIVATE).edit().clear().apply()
            activity.getSharedPreferences("takeout_json_map", AppCompatActivity.MODE_PRIVATE).edit().clear().apply()
            activity.getSharedPreferences("takeout_album_map", AppCompatActivity.MODE_PRIVATE).edit().clear().apply()
            appendTakeoutLog("분석 데이터 초기화 완료. 재분석 시작...")
            takeoutView.findViewById<Button>(R.id.btnReanalyze)?.visibility = View.GONE
            onZipSelected(uri)
        }

        takeoutView.findViewById<Button>(R.id.btnStopAnalyze).setOnClickListener {
            val stopIntent = Intent(activity, TakeoutUploadService::class.java)
            stopIntent.action = TakeoutUploadService.ACTION_STOP
            activity.startForegroundService(stopIntent)
            takeoutView.findViewById<Button>(R.id.btnStopAnalyze).visibility = View.GONE
        }

        takeoutView.findViewById<Button>(R.id.btnResumeAnalyze).setOnClickListener {
            val uri = selectedZipUri ?: return@setOnClickListener
            takeoutView.findViewById<Button>(R.id.btnResumeAnalyze).visibility = View.GONE
            takeoutView.findViewById<Button>(R.id.btnStopAnalyze).visibility = View.VISIBLE
            takeoutView.findViewById<ProgressBar>(R.id.takeoutProgressBar).visibility = View.VISIBLE
            takeoutView.findViewById<ProgressBar>(R.id.takeoutProgressBar).isIndeterminate = true
            takeoutView.findViewById<TextView>(R.id.tvTakeoutProgress).visibility = View.VISIBLE
            takeoutView.findViewById<TextView>(R.id.tvTakeoutProgress).text = "분석 이어하기..."
            takeoutView.findViewById<TextView>(R.id.tvTakeoutStatus).text = "백그라운드에서 ZIP 분석 재개 중..."
            appendTakeoutLog("분석 이어하기 시작...")

            TakeoutUploadService.progressCallback = { progress ->
                activity.runOnUiThread {
                    if (progress.total > 0 && !progress.finished) {
                        val pb = takeoutView.findViewById<ProgressBar>(R.id.takeoutProgressBar)
                        pb.isIndeterminate = false; pb.max = progress.total; pb.progress = progress.done
                        val pct = if (progress.total > 0) progress.done * 100 / progress.total else 0
                        val readMB = String.format("%.0f", progress.doneBytes / 1024.0 / 1024.0)
                        takeoutView.findViewById<TextView>(R.id.tvTakeoutProgress).text = "분석 중: $pct% (${readMB}MB)"
                    }
                }
            }
            TakeoutUploadService.logCallback = { line -> activity.runOnUiThread { appendTakeoutLog(line) } }

            val intent = Intent(activity, TakeoutUploadService::class.java)
            intent.action = TakeoutUploadService.ACTION_ANALYZE_RESUME
            intent.putExtra(TakeoutUploadService.EXTRA_ZIP_URI, uri.toString())
            if (filterStartDate != null) intent.putExtra("start_date", filterStartDate)
            if (filterEndDate != null) intent.putExtra("end_date", filterEndDate)
            activity.startForegroundService(intent)
        }
    }

    fun onZipSelected(uri: Uri) {
        selectedZipUri = uri
        val fileName = try {
            activity.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) { null } ?: uri.lastPathSegment ?: uri.toString()
        val prefs = activity.getSharedPreferences("takeout_session", AppCompatActivity.MODE_PRIVATE)
        val prevName = prefs.getString("zip_name", null)
        prefs.edit().putString("zip_uri", uri.toString()).putString("zip_name", fileName).apply()

        if (prevName != null && prevName != fileName) {
            activity.getSharedPreferences("takeout_progress", AppCompatActivity.MODE_PRIVATE).edit().clear().apply()
            activity.getSharedPreferences("takeout_media_list", AppCompatActivity.MODE_PRIVATE).edit().clear().apply()
            activity.getSharedPreferences("takeout_analyze", AppCompatActivity.MODE_PRIVATE).edit().clear().apply()
            activity.getSharedPreferences("takeout_drive_ids", AppCompatActivity.MODE_PRIVATE).edit().clear().apply()
            activity.getSharedPreferences("takeout_album_map", AppCompatActivity.MODE_PRIVATE).edit().clear().apply()
            activity.getSharedPreferences("takeout_json_map", AppCompatActivity.MODE_PRIVATE).edit().clear().apply()
            appendTakeoutLog("새 ZIP 파일 선택됨: 이전 업로드 기록 초기화")
        }

        val savedMedia = activity.getSharedPreferences("takeout_media_list", AppCompatActivity.MODE_PRIVATE)
            .getStringSet("names", emptySet()) ?: emptySet()
        if (savedMedia.isNotEmpty()) {
            val savedSize = activity.getSharedPreferences("takeout_analyze", AppCompatActivity.MODE_PRIVATE).getLong("ts", 0L)
            val sizeMB = String.format("%.1f", savedSize / 1024.0 / 1024.0)
            val sizeText = if (savedSize > 0) " (약 ${sizeMB}MB)" else ""
            lastZipInfoText = "미디어 파일: ${savedMedia.size}개${sizeText} (이전 분석 결과)"
            takeoutView.findViewById<TextView>(R.id.tvZipInfo).text = lastZipInfoText
            takeoutView.findViewById<Button>(R.id.btnStartTakeout).isEnabled = true
            takeoutView.findViewById<Button>(R.id.btnStartTakeout).visibility = View.VISIBLE
            takeoutView.findViewById<TextView>(R.id.tvTakeoutStatus).text = "이전 분석 결과 사용. 업로드 가능합니다."
            appendTakeoutLog("이전 분석 결과 재사용: 미디어 ${savedMedia.size}개${sizeText}")
            val hasResumable = activity.getSharedPreferences("takeout_progress", AppCompatActivity.MODE_PRIVATE)
                .getStringSet("uf", emptySet())?.isNotEmpty() == true
            if (hasResumable) takeoutView.findViewById<Button>(R.id.btnResumeTakeout).visibility = View.VISIBLE
            return
        }

        appendTakeoutLog("ZIP 파일 선택됨, 분석 시작...")
        takeoutView.findViewById<TextView>(R.id.tvZipInfo).text = "ZIP 파일 분석 중..."
        takeoutView.findViewById<Button>(R.id.btnStartTakeout).isEnabled = false
        takeoutView.findViewById<ProgressBar>(R.id.takeoutProgressBar).visibility = View.VISIBLE
        takeoutView.findViewById<ProgressBar>(R.id.takeoutProgressBar).isIndeterminate = true
        takeoutView.findViewById<TextView>(R.id.tvTakeoutProgress).visibility = View.VISIBLE
        takeoutView.findViewById<TextView>(R.id.tvTakeoutProgress).text = "분석 중..."
        takeoutView.findViewById<TextView>(R.id.tvTakeoutStatus).text = "백그라운드에서 ZIP 분석 중..."

        isTakeoutAnalyzing = true; isTakeoutUploading = false
        setupCallbacks()

        TakeoutUploadService.analyzeCallback = fun(mediaCount: Int, totalSize: Long, scannedCount: Int) {
            isTakeoutAnalyzing = false
            takeoutView.findViewById<ProgressBar>(R.id.takeoutProgressBar).visibility = View.GONE
            takeoutView.findViewById<TextView>(R.id.tvTakeoutProgress).visibility = View.GONE
            if (mediaCount == -2) {
                val t = "분석 중단됨 (${scannedCount}개 스캔 완료)"; takeoutView.findViewById<TextView>(R.id.tvZipInfo).text = t; lastZipInfoText = t
                takeoutView.findViewById<TextView>(R.id.tvTakeoutStatus).text = "이어하기 버튼으로 재개할 수 있습니다"
                takeoutView.findViewById<Button>(R.id.btnResumeAnalyze).visibility = View.VISIBLE
                takeoutView.findViewById<Button>(R.id.btnStopAnalyze).visibility = View.GONE
                appendTakeoutLog("분석 중단됨 - 이어하기 가능"); return
            } else if (mediaCount < 0) {
                val t = "ZIP 분석 실패"; takeoutView.findViewById<TextView>(R.id.tvZipInfo).text = t; lastZipInfoText = t
                appendTakeoutLog("ZIP 분석 실패")
            } else {
                val sizeMB = String.format("%.1f", totalSize / 1024.0 / 1024.0)
                val sizeText = if (totalSize > 0) " (약 ${sizeMB}MB)" else ""
                val t = "미디어 파일: ${mediaCount}개${sizeText} (전체 ${scannedCount}개 스캔)"; takeoutView.findViewById<TextView>(R.id.tvZipInfo).text = t; lastZipInfoText = t
                takeoutView.findViewById<Button>(R.id.btnStartTakeout).isEnabled = mediaCount > 0
                appendTakeoutLog("분석 완료: 미디어 ${mediaCount}개 발견 (전체 ${scannedCount}개 파일)")
                if (mediaCount == 0) appendTakeoutLog("미디어 파일이 없습니다. 날짜 필터를 확인하세요.")
                takeoutView.findViewById<Button>(R.id.btnReanalyze)?.visibility = View.VISIBLE
                val ac = activity.getSharedPreferences("takeout_album_map", AppCompatActivity.MODE_PRIVATE).getInt("c", 0)
                if (ac > 0) appendTakeoutLog("앨범 ${ac}개 감지됨 - 업로드 후 앨범 정리 가능")
            }
            takeoutView.findViewById<TextView>(R.id.tvTakeoutStatus).text = "ZIP 파일을 선택 후 업로드하세요"
        }

        takeoutView.findViewById<Button>(R.id.btnStopAnalyze).visibility = View.VISIBLE
        takeoutView.findViewById<Button>(R.id.btnResumeAnalyze).visibility = View.GONE

        val intent = Intent(activity, TakeoutUploadService::class.java)
        intent.action = TakeoutUploadService.ACTION_ANALYZE
        intent.putExtra(TakeoutUploadService.EXTRA_ZIP_URI, uri.toString())
        if (filterStartDate != null) intent.putExtra("start_date", filterStartDate)
        if (filterEndDate != null) intent.putExtra("end_date", filterEndDate)
        activity.startForegroundService(intent)
    }

    private fun resumeTakeoutUpload(uri: Uri) {
        isTakeoutUploading = true; isTakeoutAnalyzing = false; setupCallbacks()
        takeoutLogLines.clear()
        takeoutView.findViewById<TextView>(R.id.tvTakeoutLog).text = ""
        takeoutView.findViewById<Button>(R.id.btnStartTakeout).isEnabled = false
        takeoutView.findViewById<Button>(R.id.btnResumeTakeout).visibility = View.GONE
        takeoutView.findViewById<Button>(R.id.btnStopTakeout).visibility = View.VISIBLE
        takeoutView.findViewById<ProgressBar>(R.id.takeoutProgressBar).visibility = View.VISIBLE
        takeoutView.findViewById<ProgressBar>(R.id.takeoutProgressBar).isIndeterminate = true
        takeoutView.findViewById<TextView>(R.id.tvTakeoutProgress).visibility = View.VISIBLE
        takeoutView.findViewById<TextView>(R.id.tvTakeoutStatus).text = "이어하기 시작..."
        appendTakeoutLog("이전 중단 지점부터 이어서 업로드합니다...")

        val intent = Intent(activity, TakeoutUploadService::class.java)
        intent.action = TakeoutUploadService.ACTION_RESUME
        intent.putExtra(TakeoutUploadService.EXTRA_ZIP_URI, uri.toString())
        if (filterStartDate != null) intent.putExtra("start_date", filterStartDate)
        if (filterEndDate != null) intent.putExtra("end_date", filterEndDate)
        activity.startForegroundService(intent)
    }

    private fun startTakeoutUpload(uri: Uri) {
        isTakeoutUploading = true; isTakeoutAnalyzing = false; setupCallbacks()
        takeoutLogLines.clear()
        takeoutView.findViewById<TextView>(R.id.tvTakeoutLog).text = ""
        takeoutView.findViewById<Button>(R.id.btnStartTakeout).isEnabled = false
        takeoutView.findViewById<Button>(R.id.btnStopTakeout).visibility = View.VISIBLE
        takeoutView.findViewById<ProgressBar>(R.id.takeoutProgressBar).visibility = View.VISIBLE
        takeoutView.findViewById<ProgressBar>(R.id.takeoutProgressBar).isIndeterminate = true
        takeoutView.findViewById<TextView>(R.id.tvTakeoutProgress).visibility = View.VISIBLE
        takeoutView.findViewById<TextView>(R.id.tvTakeoutStatus).text = "업로드 시작..."

        val intent = Intent(activity, TakeoutUploadService::class.java)
        intent.action = TakeoutUploadService.ACTION_START
        intent.putExtra(TakeoutUploadService.EXTRA_ZIP_URI, uri.toString())
        if (filterStartDate != null) intent.putExtra("start_date", filterStartDate)
        if (filterEndDate != null) intent.putExtra("end_date", filterEndDate)
        activity.startForegroundService(intent)
    }

    private fun showDatePicker(isStart: Boolean) {
        val cal = Calendar.getInstance()
        DatePickerDialog(activity, { _, year, month, day ->
            val date = String.format("%04d-%02d-%02d", year, month + 1, day)
            if (isStart) { filterStartDate = date; takeoutView.findViewById<Button>(R.id.btnStartDate).text = date }
            else { filterEndDate = date; takeoutView.findViewById<Button>(R.id.btnEndDate).text = date }
            val start = filterStartDate ?: "처음"; val end = filterEndDate ?: "끝"
            takeoutView.findViewById<TextView>(R.id.tvDateRange).text = "$start ~ $end"
            activity.getSharedPreferences("takeout_media_list", AppCompatActivity.MODE_PRIVATE).edit().clear().apply()
            selectedZipUri?.let { onZipSelected(it) }
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    fun appendTakeoutLog(line: String) {
        takeoutLogLines.add(line)
        if (takeoutLogLines.size > 100) takeoutLogLines.removeAt(0)
        val tv = takeoutView.findViewById<TextView>(R.id.tvTakeoutLog)
        tv.text = takeoutLogLines.joinToString("\n")
        val sv = takeoutView.findViewById<ScrollView>(R.id.scrollTakeoutLog)
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }
    }
}
