# 아키텍처 요약

## Takeout 업로드 파이프라인
Producer 1개 (ZIP 읽기) -> Channel(버퍼 8) -> Worker 3개 (병렬 업로드)

### Producer
1. ZipArchiveInputStream으로 ZIP 엔트리 순회
2. uploaded set 확인 -> checkFileExistsSuspend로 OneDrive 존재 확인
3. 4MB 이하: 메모리, 초과: tmpFile 생성
4. Channel에 UploadItem 전송

### Worker
1. folderMutex -> createdFolders 캐시 확인 -> ensureFolderSuspend
2. uploadFileSuspend (4MB 이하: simpleUpload, 초과: chunkedUpload 10MB)
3. 실패 시 3회 재시도 (2초 간격)
4. 성공 시 uploaded set + driveItemId 저장

## 동시성
- AtomicInteger/Long: aDone, aErrors, aSkipped, aDoneBytes
- synchronizedSet: uploaded
- folderMutex (Mutex): 폴더 생성 직렬화
- synchronized(lock): createdFolders, uploaded 접근
- ConcurrentHashMap: jsonDateMap
- @Synchronized: liveLog, addUploadedFile, flushUploadedFiles

## 주요 상수
- drain 버퍼: 256KB
- readJsonSafe: 최대 64KB, 버퍼 32KB
- 업로드 임계값: 4MB
- 청크 업로드: 10MB 단위
- Channel 버퍼: 8, Worker: 3
- OkHttp: connect 30s, read 300s, write 120s
- 알림 쓰로틀: 500ms
- 스킵 로그: 50개 단위

## SharedPreferences 키
- takeout_analyze: 분석 상태 (sc, mc, ts, jc)
- takeout_media_list: 미디어 파일명 목록
- takeout_json_map: 파일명 -> 연도 맵
- takeout_progress: 업로드 완료 파일 set (키: "uf")
- takeout_drive_ids: 파일명 -> driveItem ID
- takeout_album_map: ZIP경로 -> 앨범명 (키: "c"=개수)
- takeout_session: 마지막 ZIP URI
- app_settings: 테마
- sync_progress, sync_history: 동기화 기록
