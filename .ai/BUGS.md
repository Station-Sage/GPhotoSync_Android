# 버그 트래커

## 미해결 (우선순위순)

### P0 - 업로드 불가/중단 직결
- [ ] MS 토큰 만료 시 업로드 시작 전 인증 확인 없음: 루트 폴더 생성 실패 -> 전체 중단. 업로드 시작 전 토큰 유효성 검사 필요
- [ ] MS 토큰 만료가 너무 잦음 (빌드/재설치마다 발생): EncryptedSharedPreferences가 앱 재설치 시 초기화되는지 확인 필요. 토큰 저장 방식 개선 또는 자동 갱신 로직 필요
- [ ] 실시간 로그 앱 전환 시 초기화: 서비스는 살아있으나 Activity 재생성 시 logCallback이 null이 되어 UI 로그가 리셋됨. 로그 버퍼를 서비스에 유지하고 Activity 복귀 시 복원 필요

### P1 - UX 개선
- [ ] MS 토큰 만료 시 사용자 알림 부재: 인증 실패 감지 -> liveLog 출력 -> 인증탭 상태 업데이트
- [ ] 중단 버튼 후 프로그레스바 계속 움직임
- [ ] 업로드 오류 시 화면에 원인 미표시 (폴더 생성 실패 등)
- [ ] MS 인증 간편화: 저장된 refresh_token으로 자동 재인증 시도, 실패 시에만 WebView 로그인 유도

### P2 - 안정성/성능
- [ ] sync_log.txt 쓰기 실패 (앱 권한 문제 추정, logcat으로 원인 확인 예정)
- [ ] checkFileExistsSuspend 순차 호출 -> 속도 병목
- [ ] 재분석 버튼 업로드 완료 후 표시 조건 미확인

## 해결됨
- [x] 새 업로드에서 uploaded set 무조건 스킵 -> checkFileExistsSuspend 통합
- [x] yearOnly "2015/05" 반환 -> substringBefore("/")
- [x] 폴더 생성 타임아웃 -> 재시도 3회 + Mutex + 캐싱
- [x] MS 토큰 만료 시 즉시 실패 -> 재인증으로 수동 해결
- [x] 알림 프로그레스 미갱신 -> 500ms 쓰로틀 + 첫 호출 허용
- [x] DEBUG 로그 미제거 -> 제거 완료
- [x] logWriter 오류 logcat 출력 추가
- [x] onDestroy 로그 추가
