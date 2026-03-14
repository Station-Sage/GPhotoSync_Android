# 버그 및 개선사항 (2026-03-14)

## 미해결

### P3 (개선)
- P3-12: 대용량 번들 앨범 성능 저하 (개별 POST 호출)

## 완료
- ✅ P2-12: Sync 탭 중단 버튼 작동 안 함 — scope.isActive 오용 + suspendCoroutine 취소 불가
  - 원인: scope.isActive는 SupervisorJob 상태 → syncJob.cancel() 후에도 true 유지 → 루프 탈출 불가
  - 수정: scope.isActive → isActive (코루틴 자신의 상태), suspendCoroutine → suspendCancellableCoroutine
- ✅ P2-13: Takeout 중단 시 서비스 계속 실행 — stopSelf() 누락
  - 원인: ACTION_STOP에서 job.cancel() + stopForeground() 후 stopSelf() 없음
  - 수정: stopSelf() 추가, 조건 단순화 (job?.isActive != true), STOP_FOREGROUND_REMOVE 현대화
- ✅ P2-14: Auth JSON 가져오기 시 ms_client_secret 미복원
  - 원인: importAuthFromJson()에서 ms_client_secret 복원 누락 (내보내기는 포함됨)
  - 수정: importAuthFromJson()에 ms_client_secret 복원 1줄 추가
- ✅ P2-15: isSyncing 플래그 미복원 (백그라운드 완료 시 버튼 고착)
  - 원인: SyncForegroundService에 isRunning 플래그 없음, onResume에서 서비스 상태 미확인
  - 수정: isRunning 플래그 추가, onResume에서 isRunning 기반 UI 복원 (setSyncingUI/setIdleUI)
- ✅ P2-16: MS 토큰 동시 갱신 경쟁 조건 (3 Worker 동시 refresh)
  - 원인: 3개 Worker 동시에 만료 감지 → 모두 refresh 시도 → MS refresh_token 교체로 2번째부터 실패
  - 수정: isMsRefreshing + msPendingCallbacks 큐로 동시 갱신 방지
- ✅ P2-10: 중단 버튼 분석/전처리 중 작동 안 함 (ACTION_STOP isRunning 가드)
- ✅ P2-11: MS 인증 1시간 만료 후 자동 갱신 실패 (Public Client client_secret 불필요)
- ✅ P2-5: 중단 버튼 버그 (UI 상태 4개 하위 이슈)
- ✅ P2-6: 토큰 만료 5회 연속 실패 → 즉시 중단
- ✅ P2-7: 대용량 파일 OOM → 스트리밍 청크 업로드
- ✅ P2-8: Azure 공개 클라이언트 전환 + 인증 성공
- ✅ P2-9: 로그 파일 앱 내부 저장소 이전 + SAF 내보내기
