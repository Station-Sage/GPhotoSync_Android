# 버그 트래커

## 미해결 (우선순위순)

### P0 - 업로드 불가/중단 직결
- [ ] MS 토큰 만료가 잦음: EncryptedSharedPreferences가 앱 재설치 시 초기화. 피할 수 없는 Android 정책

### P1 - UX 개선
- [ ] 중단 버튼 후 프로그레스바 계속 움직이는 문제
- [ ] MS 인증 간편화: refresh_token 자동 갱신은 구현됨, 재설치 시만 재인증 필요

### P2 - 안정성/성능
- [ ] sync_log.txt 쓰기 실패 (앱 권한 문제, logcat에 오류 출력 추가됨)
- [ ] checkFileExistsSuspend 순차 호출 -> 속도 병목

## 해결됨
- [x] WebView 인증 자동화 (브라우저+수동 코드 입력 제거)
- [x] MS/Google API 설정 JSON 업로드/내보내기
- [x] SAF 방식 인증 백업 내보내기 퍼미션 수정
- [x] 업로드 전 MS 토큰 검사 + 만료 시 알림 다이얼로그
- [x] 실시간 로그 버퍼 복원 (Activity 전환 시)
- [x] restoreState 3상태 공통함수 분리
- [x] 탭 위치 저장/복원
- [x] 로그 중복 복원 제거
- [x] 폴더 생성 타임아웃 -> 재시도 3회 + Mutex + 캐싱
- [x] yearOnly 호환성 수정
- [x] 알림 프로그레스 미갱신 수정
- [x] DEBUG 로그 제거
- [x] btnStartTakeout visibility 명시적 VISIBLE 설정
- [x] api.client public 변경
