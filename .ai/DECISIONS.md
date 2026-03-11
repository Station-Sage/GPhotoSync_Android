# 설계 결정

## D1: checkFileExistsSuspend 통합 (B안 선택)
새 업로드/이어하기 로직 통일. Producer에서 순차 호출하므로 속도 병목 있음 (P2)

## D2: conflictBehavior: fail + 409 허용
폴더 생성 시 이미 존재하면 409 반환, 정상 처리. rename 대신 fail 사용

## D3: Mutex + 재시도 3회 폴더 생성
3 Worker 동시 폴더 생성 경쟁 방지. 재시도 간격 2/4/6초

## D4: EncryptedSharedPreferences 토큰 저장
보안 우선. 앱 재설치 시 초기화되는 트레이드오프 수용

## D5: WebView 인증 (브라우저 대신)
앱 내 WebView에서 OAuth 진행. localhost 리다이렉트를 shouldOverrideUrlLoading으로 가로채기. Azure/Google 설정 변경 불필요

## D6: SAF 파일 내보내기
FileWriter 직접 쓰기 대신 Storage Access Framework 사용. Android 스토리지 권한 문제 해결

## D7: UI 상태 공통함수 분리
applyUploadingUI/applyFinishedUI/applyIdleUI 3개 함수로 화면 상태 통일. restoreState()와 progressCallback에서 동일 함수 사용

## D8: 탭 위치 저장
SharedPreferences에 last_tab 저장. onResume에서 복원하여 다른 앱 전환 시 탭 유지
