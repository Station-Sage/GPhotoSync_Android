# 현재 작업

## 상태: 업로드 테스트 진행 중 (8261개)

## 최근 완료 (2026-03-11)
- WebView 인증 자동화 (브라우저+수동 코드 입력 제거)
- MS/Google API 설정 JSON 업로드/내보내기
- SAF 방식 인증 백업 내보내기 (퍼미션 오류 해결)
- 업로드 전 MS 토큰 검사 + 만료 시 알림 다이얼로그 + 인증탭 이동
- 실시간 로그 버퍼 복원 (Activity 전환 시)
- restoreState 3상태 공통함수 분리 (applyUploadingUI/applyFinishedUI/applyIdleUI)
- 탭 위치 저장/복원 (다른 앱 전환 시)
- 로그 중복 복원 제거

## 미해결
- MS 토큰 만료가 잦음 (재설치마다 EncryptedSharedPreferences 초기화)
- 중단 버튼 후 프로그레스바 계속 움직이는 문제
- sync_log.txt 쓰기 권한 문제
- checkFileExistsSuspend 순차 호출 속도 병목
