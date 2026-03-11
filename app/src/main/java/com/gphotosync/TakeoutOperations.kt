package com.gphotosync

import android.app.NotificationManager
import android.app.Service
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.util.Collections

// ======== ZIP 분석 (TakeoutUploadService 확장) ========
internal fun TakeoutUploadService.analyzeZip(
    zipUri: android.net.Uri, startDate: String?, endDate: String?, resume: Boolean
) {
    job = scope.launch {
        try {
            var skipCount = 0; var mediaCount = 0; var totalSize = 0L; var jsonCount = 0

            if (resume) {
                val st = loadAnalyzeState()
                skipCount = st[0].toInt(); mediaCount = st[1].toInt(); totalSize = st[2]; jsonCount = st[3].toInt()
                loadJsonDateMap()
                liveLog("분석 이어하기: ${skipCount}개부터 재개 (미디어 ${mediaCount}, JSON ${jsonCount})")
            } else {
                clearAnalyzeState(); jsonDateMap.clear()
                liveLog("ZIP 파일 분석 시작...")
            }

            var zipBytes = 0L
            try { contentResolver.openFileDescriptor(zipUri, "r")?.use { zipBytes = it.statSize } } catch (_: Exception) {}
            val zipMB = if (zipBytes > 0) String.format("%.0f", zipBytes / 1048576.0) else "?"

            val cs = CountingInputStream(java.io.BufferedInputStream(contentResolver.openInputStream(zipUri)!!, 524288))
            val zis = org.apache.commons.compress.archivers.zip.ZipArchiveInputStream(cs)
            val mediaNames = mutableSetOf<String>()
            val albumMap = mutableMapOf<String, String>()
            var sc = 0
            var entry = zis.nextZipEntry

            while (entry != null && isActive) {
                if (!entry.isDirectory) {
                    sc++
                    if (sc <= skipCount) {
                        drainZipEntry(zis)
                        if (sc.rem(2000) == 0) notifyProgress("이전 위치로 이동 중... $sc/$skipCount", sc, skipCount)
                        entry = zis.nextZipEntry; continue
                    }

                    if (entry.name.endsWith(".json")) {
                        val js = readJsonSafe(zis)
                        if (js != null) {
                            val r = parseJsonMeta(js)
                            if (r != null) { jsonDateMap[r.first] = r.second; jsonCount++ }
                        }
                    } else if (isMediaFile(entry.name)) {
                        val fn = entry.name.substringAfterLast('/')
                        val fd = dateFromName(fn, entry.name)
                        if (inRange(fd, startDate, endDate)) {
                            mediaCount++; val sz = entry.size; if (sz > 0) totalSize += sz; mediaNames.add(entry.name)
                            val album = extractAlbumName(entry.name)
                            if (album != null) albumMap[entry.name] = album
                        }
                        drainZipEntry(zis)
                    } else {
                        drainZipEntry(zis)
                    }

                    if (sc.rem(500) == 0) {
                        saveAnalyzeState(sc, mediaCount, totalSize, jsonCount)
                        if (sc.rem(2000) == 0) saveJsonDateMap()
                        val pct = if (zipBytes > 0) (cs.bytesRead * 100 / zipBytes).toInt() else 0
                        val rdMB = String.format("%.0f", cs.bytesRead / 1048576.0)
                        notifyProgress("분석 $pct% ($rdMB/$zipMB MB) | 파일$sc 미디어$mediaCount",
                            if (zipBytes > 0) (cs.bytesRead / 1048576).toInt() else 0,
                            if (zipBytes > 0) (zipBytes / 1048576).toInt() else 0)
                        if (sc.rem(2000) == 0) liveLog("분석 $pct%: ${sc}개 스캔, 미디어 ${mediaCount}, JSON $jsonCount")
                        if (zipBytes > 0) TakeoutUploadService.progressCallback?.invoke(TakeoutProgress(pct, 100, 0, false, null, cs.bytesRead, 0))
                    }
                }
                entry = zis.nextZipEntry
            }
            zis.close()

            saveAnalyzeState(sc, mediaCount, totalSize, jsonCount)
            saveJsonDateMap()

            if (!isActive) {
                liveLog("분석 중단 (${sc}개 스캔 완료, 이어하기 가능)")
                android.os.Handler(android.os.Looper.getMainLooper()).post { TakeoutUploadService.analyzeCallback?.invoke(-2, totalSize, sc) }
                stopForeground(Service.STOP_FOREGROUND_DETACH); stopSelf(); return@launch
            }

            saveJsonDateMap(); saveMediaList(mediaNames); saveAlbumMap(albumMap); clearAnalyzeState()
            liveLog("분석 완료: 전체 ${sc}개, 미디어 ${mediaCount}개, JSON ${jsonCount}개")
            (getSystemService(Service.NOTIFICATION_SERVICE) as NotificationManager).notify(TakeoutUploadService.NOTIF_ID, buildDoneNotification("✅ 분석 완료: 미디어 ${mediaCount}개"))
            android.os.Handler(android.os.Looper.getMainLooper()).post { TakeoutUploadService.analyzeCallback?.invoke(mediaCount, totalSize, sc) }
            stopForeground(Service.STOP_FOREGROUND_DETACH); stopSelf()
        } catch (e: CancellationException) {
            saveJsonDateMap()
            liveLog("분석 중단됨 (이어하기 가능)")
            android.os.Handler(android.os.Looper.getMainLooper()).post { TakeoutUploadService.analyzeCallback?.invoke(-2, 0L, 0) }
            stopForeground(Service.STOP_FOREGROUND_DETACH); stopSelf()
        } catch (e: Exception) {
            liveLog("ZIP 분석 실패: ${e.message}")
            android.os.Handler(android.os.Looper.getMainLooper()).post { TakeoutUploadService.analyzeCallback?.invoke(-1, 0L, 0) }
            stopForeground(Service.STOP_FOREGROUND_DETACH); stopSelf()
        }
    }
}

// ======== 마이그레이션: 월 폴더 → 연도 폴더 ========
internal fun TakeoutUploadService.migrateMonthToYear() {
    val api = OneDriveApi(this)
    job = scope.launch {
        try {
            liveLog("월 폴더 → 연도 폴더 정리 시작...")
            notifyProgress("폴더 정리 중...", 0, 0)

            val rootId = getFolderIdSuspend(api, api.rootFolder)
            if (rootId == null) {
                liveLog("❌ 루트 폴더를 찾을 수 없음: ${api.rootFolder}")
                android.os.Handler(android.os.Looper.getMainLooper()).post { TakeoutUploadService.migrateCallback?.invoke(0, 0, 1) }
                stopForeground(Service.STOP_FOREGROUND_DETACH); stopSelf(); return@launch
            }

            val yearFolders = listChildrenSuspend(api, rootId)?.filter { it.third } ?: emptyList()
            liveLog("연도 폴더 ${yearFolders.size}개 발견")

            var totalMoved = 0; var totalErrors = 0; var totalFiles = 0

            for ((yearFolderId, yearName, _) in yearFolders) {
                if (!isActive) break
                val children = listChildrenSuspend(api, yearFolderId) ?: continue
                val monthFolders = children.filter { it.third && it.second.matches(Regex("""^\d{1,2}$|^unknown$""")) }

                if (monthFolders.isEmpty()) continue
                liveLog("📂 $yearName: 월 폴더 ${monthFolders.size}개 발견")

                for ((monthFolderId, monthName, _) in monthFolders) {
                    if (!isActive) break
                    val files = listChildrenSuspend(api, monthFolderId) ?: continue
                    val fileItems = files.filter { !it.third }

                    liveLog("  📁 $yearName/$monthName: 파일 ${fileItems.size}개 이동 중...")
                    totalFiles += fileItems.size

                    for ((fileId, fileName, _) in fileItems) {
                        if (!isActive) break
                        val ok = moveFileSuspend(api, fileId, yearFolderId)
                        if (ok) { totalMoved++ } else { totalErrors++; liveLog("  ⚠ 이동 실패: $fileName") }
                    }

                    val remaining = listChildrenSuspend(api, monthFolderId)
                    if (remaining != null && remaining.isEmpty()) {
                        deleteItemSuspend(api, monthFolderId)
                        liveLog("  🗑 빈 폴더 삭제: $yearName/$monthName")
                    }

                    notifyProgress("$yearName/$monthName 정리 중...", totalMoved, totalFiles)
                }
            }

            val msg = "폴더 정리 완료! 이동: $totalMoved, 실패: $totalErrors (전체 $totalFiles)"
            liveLog(msg)
            (getSystemService(Service.NOTIFICATION_SERVICE) as NotificationManager).notify(TakeoutUploadService.NOTIF_ID, buildDoneNotification("✅ $msg"))
            android.os.Handler(android.os.Looper.getMainLooper()).post { TakeoutUploadService.migrateCallback?.invoke(totalFiles, totalMoved, totalErrors) }
            stopForeground(Service.STOP_FOREGROUND_DETACH); stopSelf()
        } catch (e: CancellationException) {
            liveLog("폴더 정리 중단됨")
            stopForeground(Service.STOP_FOREGROUND_DETACH); stopSelf()
        } catch (e: Exception) {
            liveLog("폴더 정리 오류: ${e.message}")
            stopForeground(Service.STOP_FOREGROUND_DETACH); stopSelf()
        }
    }
}

// ======== 앨범 정리 (OneDrive bundle 앨범 생성) ========
internal fun TakeoutUploadService.organizeAlbums() {
    val api = OneDriveApi(this)
    job = scope.launch {
        try {
            val albumMap = loadAlbumMap()
            if (albumMap.isEmpty()) {
                liveLog("앨범 매핑 데이터 없음. 먼저 ZIP 분석을 실행하세요.")
                android.os.Handler(android.os.Looper.getMainLooper()).post { TakeoutUploadService.organizeCallback?.invoke(0, 0, 0) }
                stopForeground(Service.STOP_FOREGROUND_DETACH); stopSelf(); return@launch
            }

            val uploaded = Collections.synchronizedSet(getUploadedFiles())
            val albumFiles = albumMap.filter { it.key in uploaded }
            if (albumFiles.isEmpty()) {
                liveLog("앨범 정리 대상 없음 (업로드된 앨범 파일 없음)")
                android.os.Handler(android.os.Looper.getMainLooper()).post { TakeoutUploadService.organizeCallback?.invoke(0, 0, 0) }
                stopForeground(Service.STOP_FOREGROUND_DETACH); stopSelf(); return@launch
            }

            if (jsonDateMap.isEmpty()) loadJsonDateMap()

            val savedIds = loadDriveItemIds()

            val albumGroups = mutableMapOf<String, MutableList<String>>()
            for ((zipPath, albumName) in albumFiles) {
                albumGroups.getOrPut(albumName) { mutableListOf() }.add(zipPath)
            }

            val total = albumGroups.size
            var done = 0; var errors = 0
            liveLog("앨범 생성 시작: ${total}개 앨범 (${albumFiles.size}개 파일)")
            notifyProgress("앨범 생성 (${total}개)", 0, total)
            TakeoutUploadService.progressCallback?.invoke(TakeoutProgress(0, total, 0, false, null))

            for ((albumName, zipPaths) in albumGroups) {
                if (!isActive) break

                val ids = mutableListOf<String>()
                for (zp in zipPaths) {
                    val savedId = savedIds[zp]
                    if (savedId != null) { ids.add(savedId); continue }

                    val fn = zp.substringAfterLast('/')
                    val yr = yearOnly(fn, zp, jsonDateMap)
                    val filePath = "${api.rootFolder}/$yr/$fn"
                    val id = getItemIdSuspend(api, filePath)
                    if (id != null) {
                        ids.add(id)
                        saveDriveItemId(zp, id)
                    } else {
                        liveLog("⚠ id 조회 실패: $fn")
                    }
                }

                done++
                if (ids.isEmpty()) {
                    errors++
                    liveLog("❌ $albumName: 파일 id 없음")
                    TakeoutUploadService.progressCallback?.invoke(TakeoutProgress(done, total, errors, false, null))
                    continue
                }

                val ok = createAlbumSuspend(api, albumName, ids)
                if (ok) {
                    val pct = done * 100 / total
                    liveLog("📁 $albumName (${ids.size}개 파일)")
                    notifyProgress("앨범 생성 $pct% - $albumName", done, total)
                } else {
                    errors++
                    liveLog("❌ 앨범 생성 실패: $albumName")
                }
                TakeoutUploadService.progressCallback?.invoke(TakeoutProgress(done, total, errors, false, null))
            }

            val msg = "앨범 생성 완료! ${done - errors}개 앨범 (실패:$errors)"
            liveLog(msg)
            (getSystemService(Service.NOTIFICATION_SERVICE) as NotificationManager).notify(TakeoutUploadService.NOTIF_ID, buildDoneNotification("✅ $msg"))
            TakeoutUploadService.progressCallback?.invoke(TakeoutProgress(done, total, errors, true, null))
            android.os.Handler(android.os.Looper.getMainLooper()).post { TakeoutUploadService.organizeCallback?.invoke(total, done - errors, errors) }
            stopForeground(Service.STOP_FOREGROUND_DETACH); stopSelf()
        } catch (e: CancellationException) {
            liveLog("앨범 정리 중단됨")
            TakeoutUploadService.progressCallback?.invoke(TakeoutProgress(0, 0, 0, true, "중단됨"))
            stopForeground(Service.STOP_FOREGROUND_DETACH); stopSelf()
        } catch (e: Exception) {
            liveLog("앨범 정리 오류: ${e.message}")
            TakeoutUploadService.progressCallback?.invoke(TakeoutProgress(0, 0, 0, true, e.message))
            stopForeground(Service.STOP_FOREGROUND_DETACH); stopSelf()
        }
    }
}
