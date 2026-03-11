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

## D10: Worker 로그 한줄 표시 (2026-03-11)
updateWorkerLog()로 Worker별 마지막 줄 교체. logBuffer에서 index 추적. UI에서 전체 텍스트 다시 그림.

## D11: ZIP 파일 비교 파일명 기준 (2026-03-11)
SAF URI는 같은 파일도 매번 달라질 수 있음. DISPLAY_NAME으로 비교하여 분석 결과 보존.
