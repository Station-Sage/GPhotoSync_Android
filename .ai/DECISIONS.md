# 설계 결정

## D1: checkFileExistsSuspend 통합 (B안 선택)
새 업로드/이어하기 로직 통일. skipOneDriveCheck=true로 기본 로컬만 확인.

## D2: conflictBehavior: fail + 409 허용
폴더 생성 시 이미 존재하면 409 반환, 정상 처리.

## D3: 폴더별 Mutex + 재시도 3회
folderLocks (ConcurrentHashMap<String, Mutex>)로 폴더별 개별 락. 재시도 간격 2/4/6초.

## D4: EncryptedSharedPreferences 토큰 저장
보안 우선. 앱 재설치 시 초기화되는 트레이드오프 수용.

## D5: WebView 인증
앱 내 WebView에서 OAuth. localhost 리다이렉트를 shouldOverrideUrlLoading으로 가로채기.

## D6: SAF 파일 내보내기
Storage Access Framework 사용. Android 스토리지 권한 문제 해결.

## D7: UI 상태 공통함수 분리
applyUploadingUI/applyFinishedUI/applyIdleUI 3개 함수로 통일.

## D8: 탭 위치 저장
SharedPreferences last_tab. onResume에서 복원.

## D9: 대용량 파일 직렬화 (2026-03-11)
50MB 이상 파일은 largeFileMutex로 1개씩만 메모리 로드. tmpFile.readBytes() 후 즉시 delete. OOM catch로 Worker 보호.

## D10: Worker 로그 fileLog 전환 (2026-03-12)
파일별 줄 교체 방식. 같은 파일의 상태 변경(⏳→✅/❌)은 같은 줄 교체, 다음 파일은 새 줄 추가. refreshLogCallback으로 UI 전체 다시 그리기.

## D11: ZIP 파일 비교 파일명 기준 (2026-03-11)
SAF URI는 같은 파일도 매번 달라질 수 있음. DISPLAY_NAME으로 비교하여 분석 결과 보존.

## D12: TakeoutUploadService 확장함수 분리 (2026-03-11)
1030줄 → 4파일. internal 확장함수 패턴으로 서비스 내부 필드 접근 유지.

## D13: failedFolders 캐시 (2026-03-12)
폴더 생성 3회 실패 시 failedFolders set에 추가. 이후 같은 폴더 요청은 즉시 false 반환하여 불필요한 HTTP 호출과 대기 제거.

## D14: sync_log.txt MediaStore API (2026-03-12)
Android 10+ Scoped Storage 제약으로 Downloads 직접 쓰기 불가. MediaStore.Downloads로 파일 생성/검색하고 openOutputStream("wa")로 append. Termux에서 /storage/emulated/0/Download/sync_log.txt로 읽기 가능.

## D15: 토큰 refresh 마진 60초 (2026-03-12)
기존 300초(5분)에서 60초(1분)로 축소. Azure 공개 클라이언트에서 refresh 실패 시 유효한 토큰을 최대한 활용. MS 토큰 유효시간 1시간 기준 59분간 refresh 없이 동작.

## D16: MS client_secret 조건부 전송 (2026-03-12)
공개 클라이언트는 client_secret 불필요. KEY_MS_CLIENT_SECRET이 비어있으면 FormBody에 추가하지 않음. confidential 클라이언트는 기존대로 전송.
