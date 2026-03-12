package com.gphotosync

import android.app.NotificationManager
import android.app.Service
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.Collections
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream

// ======== 업로드 파이프라인 (TakeoutUploadService 확장) ========
internal fun TakeoutUploadService.startUpload(
    zipUri: android.net.Uri, startDate: String? = null, endDate: String? = null, resume: Boolean = false
) {
    val api = OneDriveApi(this)
    job = scope.launch {
        try {
            if (jsonDateMap.isEmpty()) loadJsonDateMap()
            if (jsonDateMap.isEmpty()) {
                liveLog("JSON 메타데이터 수집 중...")
                notifyProgress("메타데이터 수집 중...", 0, 0)
                val cs2 = contentResolver.openInputStream(zipUri)
                val zis2 = ZipArchiveInputStream(cs2)
                var je = zis2.nextZipEntry; var jc = 0
                while (je != null && isActive) {
                    if (!je.isDirectory && je.name.endsWith(".json")) {
                        val js = readJsonSafe(zis2)
                        if (js != null) { parseJsonMeta(js)?.let { jsonDateMap[it.first] = it.second; jc++ } }
                    } else { drainZipEntry(zis2) }
                    je = zis2.nextZipEntry
                }
                zis2.close()
                saveJsonDateMap()
                liveLog("JSON ${jc}개 수집 완료")
            }

            liveLog("미디어 파일 목록 로드 중...")
            var mediaNames = loadMediaList()
            if (mediaNames.isEmpty()) {
                liveLog("저장된 목록 없음. ZIP에서 목록 수집 중...")
                val tempNames = mutableSetOf<String>()
                val zis3 = ZipArchiveInputStream(contentResolver.openInputStream(zipUri))
                var e3 = zis3.nextZipEntry
                while (e3 != null && isActive) {
                    if (!e3.isDirectory && isMediaFile(e3.name)) {
                        val fn = e3.name.substringAfterLast('/')
                        if (inRange(dateFromName(fn, e3.name), startDate, endDate)) tempNames.add(e3.name)
                    }
                    drainZipEntry(zis3)
                    e3 = zis3.nextZipEntry
                }
                zis3.close()
                mediaNames = tempNames
                saveMediaList(mediaNames)
            }

            val total = mediaNames.size
            if (total == 0) {
                liveLog("미디어 파일 없음")
                TakeoutUploadService.progressCallback?.invoke(TakeoutProgress(0,0,0,true,"미디어 없음"))
                stopSelf(); return@launch
            }

            TakeoutUploadService.isRunning = true
            liveLog("🔑 MS 인증 확인 중...")
            val tokenOk = suspendCoroutine<Boolean> { cont ->
                TokenManager.getValidMicrosoftToken(api.client) { token ->
                    if (token != null) {
                        liveLog("✅ MS 인증 유효 (토큰: ${token.take(10)}...)")
                        cont.resume(true)
                    } else {
                        liveLog("❌ MS 인증 만료 - 인증 탭에서 재로그인 필요")
                        cont.resume(false)
                    }
                }
            }
            if (!tokenOk) {
                liveLog("⛔ 업로드 중단: MS 인증이 만료되었습니다. 인증 탭에서 Microsoft 재로그인 후 다시 시도하세요.")
                notifyProgress("❌ MS 인증 만료 - 재로그인 필요", 0, 0)
                android.os.Handler(android.os.Looper.getMainLooper()).post { TakeoutUploadService.authExpiredCallback?.invoke() }
                TakeoutUploadService.isRunning = false
                return@launch
            }
            val rootOk = ensureFolderSuspend(api, api.rootFolder)
            if (!rootOk) {
                liveLog("❌ 루트 폴더 생성 실패: ${api.rootFolder}")
                return@launch
            }
            synchronized(createdFolders) {
                val rp = api.rootFolder.split("/")
                for (i in 1..rp.size) createdFolders.add(rp.take(i).joinToString("/"))
            }
            liveLog("✅ 루트 폴더 준비 완료: ${api.rootFolder}")

            try {
                val children = suspendCoroutine<List<Triple<String, String, Boolean>>> { cont ->
                    api.listChildren(api.rootFolder) { cont.resume(it ?: emptyList()) }
                }
                val yearFolders = children.filter { it.third }.map { it.second }
                synchronized(createdFolders) {
                    yearFolders.forEach { name -> createdFolders.add("${api.rootFolder}/$name") }
                }
                liveLog("📂 기존 폴더 ${yearFolders.size}개 캐싱: ${yearFolders.take(5).joinToString()}...")
            } catch (e: Exception) {
                liveLog("⚠ 폴더 캐싱 실패 (계속 진행): ${e.message}")
            }

            TakeoutUploadService.actualUploadStartTime = 0L
            TakeoutUploadService.uploadStartTime = System.currentTimeMillis()
            liveLog("미디어 ${total}개. 업로드 시작...")
            notifyProgress("업로드 시작 (${total}개)", 0, total)
            TakeoutUploadService.currentProgress = TakeoutProgress(0, total, 0, false, null, 0, 0)
            TakeoutUploadService.progressCallback?.invoke(TakeoutProgress(0, total, 0, false, null))

            val uploaded: MutableSet<String>
            if (resume) {
                uploaded = Collections.synchronizedSet(getUploadedFiles())
                if (uploaded.isNotEmpty()) liveLog("이어하기: ${uploaded.size}개 스킵")
            } else {
                uploaded = Collections.synchronizedSet(getUploadedFiles())
                if (uploaded.isNotEmpty()) liveLog("기존 완료 ${uploaded.size}개는 스킵합니다")
            }

            val aDone = AtomicInteger(0); val aErrors = AtomicInteger(0)
            val aSkipped = AtomicInteger(0); val aDoneBytes = AtomicLong(0L)
            val lock = Any()

            val channel = Channel<UploadItem>(8)

            // Producer: ZIP에서 읽어서 Channel에 전달
            val producer = launch {
                val zis4 = ZipArchiveInputStream(java.io.BufferedInputStream(contentResolver.openInputStream(zipUri), 524288))
                var e4 = zis4.nextZipEntry
                try {
                    while (e4 != null && isActive) {
                        if (!e4.isDirectory && e4.name in mediaNames) {
                            val fn = e4.name.substringAfterLast('/')
                            val ym = yearOnly(fn, e4.name, jsonDateMap)
                            val fp = "${api.rootFolder}/$ym"

                            if (e4.name in uploaded) {
                                if (resume) {
                                    if (TakeoutUploadService.skipOneDriveCheck) {
                                        val d = aDone.incrementAndGet()
                                        val s = aSkipped.incrementAndGet()
                                        if (s % 50 == 0 || s == 1) {
                                            val pct = if (total > 0) d * 100 / total else 0
                                            notifyProgress("스킵 중 ($pct%) ${s}개 완료", d, total)
                                            TakeoutUploadService.progressCallback?.invoke(TakeoutProgress(d, total, aErrors.get(), false, null, aDoneBytes.get(), s))
                                        }
                                        drainZipEntry(zis4); e4 = zis4.nextZipEntry; continue
                                    }
                                    val checkPath = "$fp/$fn"
                                    val exists = checkFileExistsSuspend(api, checkPath)
                                    if (exists == null) {
                                        synchronized(lock) { uploaded.remove(e4.name) }
                                        liveLog("🔄 재업로드 필요: $fn (OneDrive에서 삭제됨)")
                                    } else {
                                        val d = aDone.incrementAndGet()
                                        val s = aSkipped.incrementAndGet()
                                        if (s % 50 == 0 || s == 1) {
                                            val pct = if (total > 0) d * 100 / total else 0
                                            notifyProgress("스킵 중 ($pct%) ${s}개 완료", d, total)
                                            TakeoutUploadService.progressCallback?.invoke(TakeoutProgress(d, total, aErrors.get(), false, null, aDoneBytes.get(), s))
                                        }
                                        drainZipEntry(zis4); e4 = zis4.nextZipEntry; continue
                                    }
                                } else {
                                    if (TakeoutUploadService.skipOneDriveCheck) {
                                        val d = aDone.incrementAndGet()
                                        val s = aSkipped.incrementAndGet()
                                        if (s % 50 == 0 || s == 1) {
                                            val pct = if (total > 0) d * 100 / total else 0
                                            notifyProgress("스킵 중 ($pct%) ${s}개 완료", d, total)
                                            TakeoutUploadService.progressCallback?.invoke(TakeoutProgress(d, total, aErrors.get(), false, null, aDoneBytes.get(), s))
                                        }
                                        drainZipEntry(zis4); e4 = zis4.nextZipEntry; continue
                                    }
                                    val checkPath2 = "$fp/$fn"
                                    val exists2 = checkFileExistsSuspend(api, checkPath2)
                                    if (exists2 == null) {
                                        synchronized(lock) { uploaded.remove(e4.name) }
                                        liveLog("🔄 재업로드 필요: $fn (OneDrive에서 삭제됨)")
                                    } else {
                                        val d = aDone.incrementAndGet()
                                        val s = aSkipped.incrementAndGet()
                                        if (s % 50 == 0 || s == 1) {
                                            val pct = if (total > 0) d * 100 / total else 0
                                            notifyProgress("스킵 중 ($pct%) ${s}개 완료", d, total)
                                            TakeoutUploadService.progressCallback?.invoke(TakeoutProgress(d, total, aErrors.get(), false, null, aDoneBytes.get(), s))
                                        }
                                        drainZipEntry(zis4); e4 = zis4.nextZipEntry; continue
                                    }
                                }
                            }

                            val threshold = 4 * 1024 * 1024
                            val baos = java.io.ByteArrayOutputStream(65536)
                            var tmpFile: java.io.File? = null
                            var tmpOut: java.io.OutputStream? = null
                            val buf = ByteArray(32768)
                            var totalRead = 0L
                            var n = zis4.read(buf)
                            while (n != -1) {
                                totalRead += n.toLong()
                                if (tmpOut != null) {
                                    tmpOut.write(buf, 0, n)
                                } else if (totalRead > threshold) {
                                    tmpFile = java.io.File(cacheDir, "takeout_tmp_${System.currentTimeMillis()}")
                                    tmpOut = tmpFile.outputStream().buffered()
                                    tmpOut.write(baos.toByteArray())
                                    tmpOut.write(buf, 0, n)
                                } else {
                                    baos.write(buf, 0, n)
                                }
                                n = zis4.read(buf)
                            }
                            tmpOut?.flush(); tmpOut?.close()
                            val data: ByteArray
                            if (tmpFile != null) {
                                baos.reset()
                                data = ByteArray(0)
                            } else {
                                data = baos.toByteArray()
                                baos.reset()
                            }

                            try {
                                channel.send(UploadItem(e4.name, fn, fp, data, totalRead, tmpFile))
                            } catch (ce: kotlinx.coroutines.CancellationException) {
                                tmpFile?.delete()
                                throw ce
                            }
                        } else { drainZipEntry(zis4) }
                        e4 = zis4.nextZipEntry
                    }
                } finally { zis4.close(); channel.close() }
            }

            // Workers: Channel에서 받아 병렬 업로드
            val workers = (1..3).map { workerId ->
                launch {
                    for (item in channel) {
                        try {
                            val pathInfo = item.fp.substringAfter(api.rootFolder + "/")
                            liveLog("[W$workerId] ⏳ ${item.fn} ($pathInfo) 업로드 중...")

                            val folderOk = run {
                                if (item.fp in createdFolders) return@run true
                                if (item.fp in failedFolders) return@run false
                                val fLock = folderLocks.getOrPut(item.fp) { Mutex() }
                                fLock.withLock {
                                    if (item.fp in createdFolders) return@withLock true
                                    val ok = ensureFolderSuspend(api, item.fp)
                                    if (ok) {
                                        val parts = item.fp.split("/")
                                        for (i in 1..parts.size) {
                                            createdFolders.add(parts.take(i).joinToString("/"))
                                        }
                                    }
                                    if (!ok) synchronized(failedFolders) { failedFolders.add(item.fp) }
                                    ok
                                }
                            }
                            var driveItemId: String? = null
                            if (folderOk) {
                                val isLarge = item.fileSize > 50 * 1024 * 1024
                                try {
                                    if (isLarge) {
                                        liveLog("[W$workerId] ⏳ ${item.fn} 대용량(${item.fileSize / 1048576}MB) 업로드 중...")
                                        largeFileMutex.lock()
                                    }
                                    val uploadData = if (item.tmpFile != null && item.tmpFile.exists()) {
                                        val bytes = item.tmpFile.readBytes()
                                        item.tmpFile.delete()
                                        bytes
                                    } else item.data
                                    for (attempt in 1..3) {
                                        driveItemId = uploadFileSuspend(api, uploadData, item.fn, item.fp)
                                        if (driveItemId != null) break
                                        if (attempt < 3) {
                                            liveLog("[W$workerId] ⚠ ${item.fn} 재시도 $attempt/3...")
                                            kotlinx.coroutines.delay(1000L * attempt)
                                        }
                                    }
                                } catch (oom: OutOfMemoryError) {
                                    liveLog("[W$workerId] ❌ ${item.fn} 메모리 부족 (${item.fileSize / 1048576}MB)")
                                    System.gc()
                                } finally {
                                    if (isLarge) largeFileMutex.unlock()
                                    item.tmpFile?.delete()
                                }
                            }

                            if (driveItemId != null) {
                                val d = aDone.incrementAndGet()
                                aDoneBytes.addAndGet(item.fileSize)
                                if (TakeoutUploadService.actualUploadStartTime == 0L) TakeoutUploadService.actualUploadStartTime = System.currentTimeMillis()
                                synchronized(lock) {
                                    uploaded.add(item.zipName)
                                    addUploadedFile(item.zipName)
                                }
                                saveDriveItemId(item.zipName, driveItemId)
                                val pct = if (total > 0) d * 100 / total else 0
                                val sizeKB = String.format("%.1f", item.fileSize / 1024.0)
                                val elapsedSec = if (TakeoutUploadService.actualUploadStartTime > 0) (System.currentTimeMillis() - TakeoutUploadService.actualUploadStartTime) / 1000.0 else 0.0
                                val avgSpeed = if (elapsedSec > 0) String.format("%.1f", aDoneBytes.get() / 1048576.0 / elapsedSec) else "?"
                                liveLog("[W$workerId] ✅ ${item.fn} (${sizeKB}KB) ${avgSpeed}MB/s")
                                notifyProgress("$pct% ($d/$total) ${avgSpeed}MB/s", d, total)
                                val prog = TakeoutProgress(d, total, aErrors.get(), false, null, aDoneBytes.get(), aSkipped.get())
                                TakeoutUploadService.currentProgress = prog
                                TakeoutUploadService.progressCallback?.invoke(prog)
                            } else {
                                aErrors.incrementAndGet()
                                val reason = if (!folderOk) "폴더 생성 실패" else "업로드 실패 (3회 재시도)"
                                liveLog("[W$workerId] ❌ ${item.fn} - $reason")
                                val prog = TakeoutProgress(aDone.get(), total, aErrors.get(), false, null, aDoneBytes.get(), aSkipped.get())
                                TakeoutUploadService.currentProgress = prog
                                TakeoutUploadService.progressCallback?.invoke(prog)
                            }
                        } finally { item.tmpFile?.delete() }
                    }
                }
            }

            producer.join()
            workers.forEach { it.join() }

            flushUploadedFiles()
            flushDriveItemIds()

            val done = aDone.get(); val errors = aErrors.get(); val skipped = aSkipped.get(); val doneBytes = aDoneBytes.get()
            val elapsedSec = if (TakeoutUploadService.actualUploadStartTime > 0) (System.currentTimeMillis() - TakeoutUploadService.actualUploadStartTime) / 1000.0 else 0.0
            val avgSpeed = if (elapsedSec > 0) String.format("%.1f", doneBytes / 1048576.0 / elapsedSec) else "?"
            val msg = "업로드 완료! ${done - skipped}개 업로드, ${skipped}개 스킵, ${errors}개 실패 (평균 ${avgSpeed}MB/s)"
            liveLog(msg)
            (getSystemService(Service.NOTIFICATION_SERVICE) as NotificationManager).notify(TakeoutUploadService.NOTIF_ID, buildDoneNotification("✅ $msg"))
            TakeoutUploadService.progressCallback?.invoke(TakeoutProgress(done, total, errors, true, null, doneBytes, skipped))
            TakeoutUploadService.isRunning = false
            withContext(Dispatchers.Main) { stopForeground(Service.STOP_FOREGROUND_DETACH); stopSelf() }
        } catch (e: CancellationException) {
            TakeoutUploadService.isRunning = false
            flushUploadedFiles()
            flushDriveItemIds()
            liveLog("업로드 중단됨 (이어하기 가능)")
            TakeoutUploadService.progressCallback?.invoke(TakeoutProgress(0,0,0,true,"중단됨 - 이어하기 가능"))
            stopForeground(Service.STOP_FOREGROUND_DETACH); stopSelf()
        } catch (e: Exception) {
            liveLog("오류: ${e.message}")
            TakeoutUploadService.progressCallback?.invoke(TakeoutProgress(0,0,0,true,e.message))
            stopForeground(Service.STOP_FOREGROUND_DETACH); stopSelf()
        }
    }
}
