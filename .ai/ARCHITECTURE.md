# 아키텍처

## Takeout 업로드 파이프라인
1 Producer (ZIP 스트리밍) -> Channel(buffer=8) -> 3 Workers (병렬 업로드)

### Producer
ZIP 엔트리 순회 -> 미디어 파일 필터링 -> JSON 메타데이터로 연도 추출 -> UploadItem 생성 -> Channel 전송

### Worker
Channel에서 UploadItem 수신 -> folderMutex로 폴더 생성 (캐시 확인) -> uploadFileSuspend (4MB 이하: 단순, 초과: 10MB 청크) -> 결과 기록

### 동시성
- AtomicInteger/Long: aDone, aDoneBytes, aErrors, aSkipped
- synchronized: createdFolders set
- Mutex: folderMutex (폴더 생성 경쟁 방지)
- ConcurrentHashMap: 없음 (synchronized로 대체)

### 인증 흐름 (개선됨)
앱 내 WebView에서 OAuth 로그인 -> localhost 리다이렉트 자동 가로채기 -> code 추출 -> 토큰 교환 -> EncryptedSharedPreferences 저장
업로드 시작 전 토큰 유효성 검사 -> 만료 시 알림 다이얼로그 -> 인증탭 자동 이동

### UI 상태 관리
공통함수 3개로 통일:
- applyUploadingUI(progress): 업로드 중 UI (프로그레스바, 중단 버튼)
- applyFinishedUI(progress): 완료 UI (결과 표시, 앨범/마이그레이션 버튼)
- applyIdleUI(): 대기 UI (업로드/이어하기 버튼)
탭 위치 SharedPreferences 저장/복원

### 주요 상수
- 업로드 임계값: 4MB (초과 시 청크)
- 청크 크기: 10MB
- Channel 버퍼: 8
- OkHttp 타임아웃: connect 30s, read 300s, write 120s
- 로그 버퍼: 200개 (Activity 전환 시 복원)
- 알림 쓰로틀: 500ms
