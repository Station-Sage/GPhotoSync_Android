# 아키텍처

## 앱 구조
Android Service 기반. 4개 탭: Sync(실시간), Auth(인증), Info(정보), Takeout(대량 업로드).

## Takeout 업로드 파이프라인

ZIP file
  ↓ (ZipArchiveInputStream, 512KB buffer)
Producer → Channel(buffer=8) → 3 Workers → OneDrive

- Producer: ZIP 순회 → 미디어 필터 → 연도 추출 → UploadItem 생성
- Worker: 폴더 생성(Mutex) → 업로드(4MB↓ 단순, 4MB↑ 10MB 청크) → 결과 기록
- 대용량(50MB+): largeFileMutex로 직렬화
- tmpFile 존재 시: uploadFileFromFile (File 스트리밍, OOM 방지)

## 동시성
- AtomicInteger/Long: 카운터 (done, errors, skipped, bytes)
- folderLocks: ConcurrentHashMap<String, Mutex> (폴더별 락)
- largeFileMutex: 50MB+ 파일 직렬
- createdFolders/failedFolders: synchronized set (캐시)

## 인증
WebView OAuth → localhost 리다이렉트 가로채기 → 토큰 교환.
EncryptedSharedPreferences 저장. 만료 60초 전 refresh 시도.
refresh 실패 시 5회 연속 업로드 실패 감지 → 즉시 중단 + 재인증 팝업.

## 로그
liveLog/fileLog → MediaStore Downloads/sync_log.txt + logBuffer(200) + UI 콜백.
fileLog: Worker별 줄 교체 (⏳→✅ 같은 줄).

## 주요 상수
업로드 임계값 4MB, 청크 10MB, 대용량 50MB, 재시도 3회, Channel 8,
OkHttp(connect 30s, read 300s, write 120s), 로그 200개, 알림 500ms.

## 파일 분리 패턴
TakeoutUploadService의 기능을 internal 확장함수로 분리.
서비스 내부 필드에 직접 접근 가능. 현재 4파일 (Service, Pipeline, Operations, Utils).
