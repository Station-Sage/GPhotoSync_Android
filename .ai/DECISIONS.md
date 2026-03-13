# 설계 결정

## D1: skipOneDriveCheck=true 기본값
이어하기 시 로컬 SharedPreferences만 확인. OneDrive HTTP 호출 생략으로 스킵 속도 대폭 개선.

## D2: conflictBehavior=fail + 409 허용
폴더 생성 시 이미 존재하면 409, 정상 처리. 불필요한 GET 확인 제거.

## D3: 폴더별 Mutex + 3회 재시도
ConcurrentHashMap<String, Mutex>. 간격 2/4/6초. failedFolders 캐시로 실패 폴더 즉시 스킵.

## D4: EncryptedSharedPreferences 토큰 저장
AES-256-GCM. 앱 재설치 시 초기화 수용.

## D5: WebView OAuth + localhost 리다이렉트
shouldOverrideUrlLoading으로 code 추출.

## D6: SAF 파일 내보내기
Android Scoped Storage 대응.

## D7: UI 상태 3함수
applyUploadingUI / applyFinishedUI / applyIdleUI.

## D8: 탭 위치 SharedPreferences 저장/복원

## D9: 대용량 파일 직렬화 + File 스트리밍 (03-13)
50MB+ largeFileMutex. tmpFile이 있으면 uploadFileFromFile로 10MB 청크 스트리밍. readBytes() 제거로 OOM 해결.

## D10: Worker fileLog 줄 교체 (03-12)
⏳→✅/❌ 같은 줄 교체, 다음 파일은 새 줄. refreshLogCallback으로 UI 갱신.

## D11: ZIP 비교 DISPLAY_NAME 기준
SAF URI 변동 대응.

## D12: 확장함수 분리 패턴 (03-11)
1030줄 → 4파일. internal 확장함수로 서비스 필드 접근 유지.

## D13: sync_log.txt MediaStore API (03-12)
Downloads에 append. Termux 접근 가능.

## D14: 토큰 refresh 마진 60초 (03-12)
300초→60초. 유효 토큰 최대 활용.

## D15: 토큰 만료 5회 연속 실패 감지 (03-13)
Worker에서 consecutiveFailures 카운트. 5회 시 토큰 검증 → 만료면 job.cancel + authExpiredCallback.

## D16: client_secret 조건부 전송 (03-12)
KEY_MS_CLIENT_SECRET 비어있으면 FormBody에 미포함.
