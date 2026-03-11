# 아키텍처

## Takeout 업로드 파이프라인
1 Producer (ZIP 스트리밍) → Channel(buffer=8) → 3 Workers (병렬 업로드)

### Producer
ZIP 엔트리 순회 → 미디어 파일 필터링 → JSON 메타데이터로 연도 추출 → UploadItem 생성 → Channel 전송

### Worker
Channel에서 UploadItem 수신 → 폴더별 Mutex로 폴더 생성 (캐시 확인) → uploadFileSuspend (4MB 이하: 단순, 초과: 10MB 청크 + 3회 재시도) → 결과 기록. 대용량(50MB+) 파일은 largeFileMutex로 1개씩만 메모리 로드.

### 동시성
- AtomicInteger/Long: aDone, aDoneBytes, aErrors, aSkipped
- synchronized: createdFolders set
- folderLocks (ConcurrentHashMap<String, Mutex>): 폴더별 개별 락
- largeFileMutex: 50MB 이상 파일 직렬화 (OOM 방지)
- ConcurrentHashMap: jsonDateMap

### 인증 흐름
앱 내 WebView OAuth → localhost 리다이렉트 가로채기 → code 추출 → 토큰 교환 → EncryptedSharedPreferences 저장. 업로드 시작 전 토큰 유효성 검사 → 만료 시 AlertDialog → 인증탭 자동 이동

### UI 상태 관리
공통함수 3개: applyUploadingUI / applyFinishedUI / applyIdleUI. Activity 재생성 시 restorePreviousSession()에서 isRunning 확인 → setupCallbacks + applyUploadingUI로 즉시 복원. 탭 위치 SharedPreferences 저장/복원.

### Worker 로그
updateWorkerLog()로 Worker별 마지막 줄 교체 (진행중→완료를 한줄로). liveLog()는 일반 로그용.

### 주요 상수
- 업로드 임계값: 4MB (초과 시 청크)
- 청크 크기: 10MB, 실패 시 3회 재시도
- 대용량 파일 기준: 50MB (largeFileMutex 적용)
- Channel 버퍼: 8
- OkHttp 타임아웃: connect 30s, read 300s, write 120s
- 로그 버퍼: 200개 (Activity 전환 시 복원)
- 알림 쓰로틀: 500ms
- 스킵: skipOneDriveCheck=true (로컬 SharedPreferences만 확인)
