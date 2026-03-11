# 현재 작업

## 상태: 업로드 테스트 진행 중 (28GB ZIP, 8261개)

## 최근 완료 (2026-03-11)
1. .ai/ 컨텍스트 문서 6개 + PROMPT_TEMPLATE 생성
2. ai-patch 스크립트 생성 (diff 기반 패치 적용)
3. DEBUG 로그 제거 (ensureFolderSuspend)

## 미해결
- [ ] MS 토큰 만료 시 사용자 알림 부재 (인증 실패 체크, 로그 출력, 인증탭 상태 업데이트)
- [ ] 중단 시 프로그레스바 계속 움직이는 문제
- [ ] 재분석 버튼 업로드 완료 후 표시 확인
- [ ] 업로드 속도 최적화 (checkFileExistsSuspend 병목)
- [ ] sync_log.txt 쓰기 권한 문제\n