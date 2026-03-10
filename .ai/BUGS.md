# 버그 트래커

## 미해결
- [ ] 중단 버튼 후 프로그레스바 계속 움직임
- [ ] sync_log.txt 쓰기 실패 (앱 권한 문제 추정)
- [ ] 재분석 버튼 업로드 완료 후 표시 조건 미확인
- [ ] checkFileExistsSuspend가 Producer에서 순차 호출 -> 속도 병목
- [ ] DEBUG 로그 미제거 상태

## 해결됨
- [x] 새 업로드에서 uploaded set 무조건 스킵 -> checkFileExistsSuspend 통합
- [x] yearOnly "2015/05" 반환 -> substringBefore("/")
- [x] 폴더 생성 타임아웃 -> 재시도 3회 + Mutex + 캐싱
- [x] MS 토큰 만료 시 즉시 실패 -> 재인증으로 해결
- [x] 알림 프로그레스 미갱신 -> 500ms 쓰로틀 + 첫 호출 허용
