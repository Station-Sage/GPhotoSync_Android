# 버그 및 개선사항 (2026-03-14)

## 미해결

### P3 (개선)
- P3-12: 대용량 번들 앨범 성능 저하 (개별 POST 호출)

## 완료
- ✅ P2-10: 중단 버튼 분석/전처리 중 작동 안 함
  - 원인: ACTION_STOP 핸들러가 isRunning 가드로만 체크 → 분석 중 & 업로드 전처리 중 isRunning=false여서 무시됨
  - 수정: `if (!isRunning && job?.isActive != true) return` 으로 변경 (job 활성 여부도 함께 확인)
- ✅ P2-11: MS 인증 1시간 만료 후 자동 갱신 실패
  - 원인: getValidMicrosoftToken에서 client_secret 없으면 즉시 null 반환
  - 수정: 공개 클라이언트(PKCE 방식)는 client_secret 없이 refresh_token만으로 갱신 가능 — client_secret 있는 경우에만 포함
- ✅ P2-5: 중단 버튼 버그 (UI 상태 4개 하위 이슈)
- ✅ P2-6: 토큰 만료 5회 연속 실패 → 즉시 중단
- ✅ P2-7: 대용량 파일 OOM → 스트리밍 청크 업로드
- ✅ P2-8: Azure 공개 클라이언트 전환 + 인증 성공
- ✅ P2-9: 로그 파일 앱 내부 저장소 이전 + SAF 내보내기
