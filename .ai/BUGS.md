# 버그 트래커

## 미해결
- [ ] MS 토큰 만료 시 사용자 알림 부재: ensureFolder/uploadFile에서 토큰 null이면 즉시 false 반환하지만 사용자에게 원인 미표시. 인증 실패 감지 -> liveLog 출력 -> 인증탭 상태 업데이트 필요
- [ ] 중단 버튼 후 프로그레스바 계속 움직임
- [ ] sync_log.txt 쓰기 실패 (앱 권한 문제 추정)
- [ ] 재분석 버튼 업로드 완료 후 표시 조건 미확인
- [ ] checkFileExistsSuspend가 Producer에서 순차 호출 -> 속도 병목

## 해결됨
- [x] 새 업로드에서 uploaded set 무조건 스킵 -> checkFileExistsSuspend 통합
- [x] yearOnly "2015/05" 반환 -> substringBefore("/")
- [x] 폴더 생성 타임아웃 -> 재시도 3회 + Mutex + 캐싱
- [x] MS 토큰 만료 시 즉시 실패 -> 재인증으로 해결 (코드 변경 필요: 알림 추가)
- [x] 알림 프로그레스 미갱신 -> 500ms 쓰로틀 + 첫 호출 허용
- [x] DEBUG 로그 미제거 -> 제거 완료\n