# 알려진 버그 및 개선사항 (2026-03-11)

## P1 — 즉시 수정 (사용성 직결)
~~1. Activity 재생성 시 UI 초기화~~ → ✅ 수정 완료
~~2. 업로드 속도 느림 (스킵 최적화)~~ → ✅ skipOneDriveCheck=true
~~3. 대용량 동영상 업로드 중 앱 멈춤/끊김~~ → ✅ 청크 재시도 + largeFileMutex + OOM catch

## P2 — 중요 (기능 완성도)
4. **인증 탭 토큰 만료 미반영** — isMicrosoftAuthed()가 토큰 존재만 확인, 만료되어도 "인증 완료" 표시
5. **MS 재인증 후 업로드 자동 재시작 안 됨** — 수동으로 업로드 버튼 다시 클릭 필요
6. **업로드 중 MS 토큰 만료 처리** — 시작 전에만 검증, 업로드 중간 만료 시 정지 후 재개 미지원
7. **알림창 진행 상태와 앱 내 프로그레스 불일치** — notifyProgress(500ms)와 progressCallback 쓰로틀 차이

## P3 — 개선 (안정성/편의)
8. **sync_log.txt 쓰기 퍼미션 문제** — logToFile 작동 안 됨
9. **listChildren 페이징** — @odata.nextLink 처리됨, 실사용 확인 필요
10. **취소 후 프로그레스바 리셋 미확인**
11. **대형 Bundle 앨범 성능** — 개별 POST, 수백 개 앨범 시 느림
12. **토큰 손실 원인 조사** — 앱 사용 중 토큰 사라지는 케이스

## 코드 품질
~~13. TakeoutUploadService.kt 1000줄 초과~~ → ✅ 4파일 분리 완료 (Service 331, Pipeline 357, Operations 268, Utils 117)

## 수정 완료 (2026-03-11)
- restoreState() 3상태 분기 통합
- btnStartTakeout visibility 복원
- 로그 버퍼 중복 복원 → clear 후 restore
- 탭 위치 저장/복원
- folderMutex → 폴더별 락 (folderLocks ConcurrentHashMap)
- ZIP 파일 비교 URI→파일명
- MS 토큰 검증 OkHttpClient api.client 재사용
- 속도 표시 actualUploadStartTime 기준
- P1-1~P1-4 전부 수정
- Worker 로그 한줄 표시 (updateWorkerLog)
- TakeoutUploadService.kt → 4파일 분리
