# 버그 및 개선사항 (2026-03-13)

## 미해결

### P2 (중요)
- P2-5: 중단 버튼 버그 (복합 이슈, 아래 상세)
  - (a) 중단 후 UI가 "ZIP 파일 선택" 상태로 돌아감 → applyIdleUI() 대신 "중단됨/이어하기" UI를 보여야 함
  - (b) 알림이 "업로드 중"으로 남음 → stopForeground 호출이 job.cancel()보다 늦게 실행됨 (500ms delay 후 stopSelf인데, 코루틴 취소와 타이밍 불일치)
  - (c) 2회 클릭 시 앱 종료 → 이미 cancel된 job에 다시 cancel + stopSelf 호출. isRunning=false 체크 후 return하지만 stopSelf()가 service를 죽임
  - (d) CancellationException 핸들러(372줄)에서 progressCallback으로 "중단됨" 전달하지만, UI 쪽에서 isRunning=false이면 applyIdleUI()를 호출하여 덮어씀 (TakeoutTabState:150)
  - 수정 방향:
    1. ACTION_STOP: isRunning=false → job.cancel() → 알림 즉시 제거(stopForeground) → stopSelf 제거 (파이프라인 종료 시 stopSelf)
    2. Pipeline CancellationException: "중단됨" 상태 전달 + stopForeground + stopSelf
    3. TakeoutTabState: isRunning=false + finished=false + errorMessage에 "중단" 포함 → applyStoppedUI (이어하기 버튼 표시, ZIP 선택 숨김)
    4. 2회 클릭 방지: isRunning=false이면 즉시 return (stopSelf 호출 안 함)
- P2-9: 로그 파일을 앱 내부 저장소로 이전 (Play Store 정책 대응)
  - logToFile: TokenManager, GooglePhotosApi, OneDriveApi, SyncForegroundService
  - liveLog/fileLog: TakeoutUploadService (MediaStore → filesDir)
  - MainActivity: Downloads 직접 접근 제거
  - 내보내기 버튼 추가 (SAF)

### P3 (개선)
- P3-12: 대용량 번들 앨범 성능 저하 (개별 POST 호출)

## 완료
- ✅ P2-6: 토큰 만료 5회 연속 실패 → 즉시 중단
- ✅ P2-7: 대용량 파일 OOM → 스트리밍 청크 업로드
- ✅ P2-8: Azure 공개 클라이언트 전환 + 인증 성공
