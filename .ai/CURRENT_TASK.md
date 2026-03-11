# 현재 작업 (2026-03-11)

## 상태: P1 수정 완료 — 실기기 테스트 중

## 오늘 완료한 P1 수정
- [x] P1-1: Activity 재생성 시 UI 복원 (restorePreviousSession → setupCallbacks + applyUploadingUI)
- [x] P1-2: skipOneDriveCheck 기본 true (스킵 시 HTTP 호출 제거)
- [x] P1-3: 청크 업로드 3회 재시도 (onFailure + 5xx 서버 에러)
- [x] P1-4: 대용량 파일 OOM 방지 (largeFileMutex + tmpFile 즉시삭제 + OOM catch)
- [x] Worker 로그 한줄 표시 (updateWorkerLog — 진행중→완료 줄 교체)

## 검증 필요 (실기기)
- [x] 같은 ZIP 재선택 시 분석 결과 재사용 ✅
- [x] 업로드 속도 표시 정상 (0.5MB/s) ✅
- [x] 폴더 생성 실패 해결 ✅
- [ ] 앱 전환 후 복귀 시 UI 복원 (P1-1)
- [ ] 이어하기 스킵 속도 개선 (P1-2)
- [ ] 대용량 동영상 17% 멈춤 해결 (P1-3, P1-4)
- [ ] Worker 로그 한줄 교체 동작 확인

## 다음 작업
- TakeoutUploadService.kt 1000줄 초과 → 파일 분리
- P2 버그 수정 (BUGS.md 참고)
