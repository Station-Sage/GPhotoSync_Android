# 알려진 버그 및 개선사항 (2026-03-12)

## P1 — 즉시 수정 (사용성 직결)
~~1. Activity 재생성 시 UI 초기화~~ → ✅ 수정 완료
~~2. 업로드 속도 느림 (스킵 최적화)~~ → ✅ skipOneDriveCheck=true
~~3. 대용량 동영상 업로드 중 앱 멈춤/끊김~~ → ✅ 청크 재시도 + largeFileMutex + OOM catch

## P2 — 중요 (기능 완성도)
4. **인증 탭 토큰 만료 미반영** — isMicrosoftAuthed()가 토큰 존재만 확인, 만료되어도 "인증 완료" 표시
5. **MS 재인증 후 업로드 자동 재시작 안 됨** — 수동으로 업로드 버튼 다시 클릭 필요
6. **업로드 중 MS 토큰 만료 시 즉시 중단 + 팝업** — 현재: 만료된 토큰으로 3회 재시도 반복하며 전부 실패. 개선: 토큰 갱신 실패 감지 즉시 업로드 중단, "토큰 갱신 실패 — 재인증 필요" AlertDialog 표시, 인증 탭 이동 버튼 포함. 이어하기로 재개 가능해야 함.
7. **알림창 진행 상태와 앱 내 프로그레스 불일치** — notifyProgress(500ms)와 progressCallback 쓰로틀 차이
8. **Azure 앱 등록 "공개 클라이언트 흐름 허용" 설정 필요** — refresh_token 갱신 시 client_secret 요구 에러(AADSTS70002). Azure Portal에서 "공개 클라이언트 흐름 허용=예" 필요. 앱 내 안내 또는 자동 감지 추가.

## P3 — 개선 (안정성/편의)
~~8. sync_log.txt 쓰기 퍼미션 문제~~ → ✅ MediaStore API로 Downloads 직접 쓰기
9. **listChildren 페이징** — @odata.nextLink 처리됨, 실사용 확인 필요
10. **취소 후 프로그레스바 리셋 미확인**
11. **대형 Bundle 앨범 성능** — 개별 POST, 수백 개 앨범 시 느림
12. **토큰 손실 원인 조사** — 앱 사용 중 토큰 사라지는 케이스
13. **분석 완료 시 UI 업데이트 지연** — 알림에는 완료 표시되나 앱 화면은 97% 유지, 앱 전환 후 복귀 시 정상 표시

## 코드 품질
~~14. TakeoutUploadService.kt 1000줄 초과~~ → ✅ 4파일 분리 완료
~~15. updateWorkerLog/updateLogCallback 미사용 코드~~ → ✅ 제거 완료

## 수정 완료 (2026-03-12)
- sync_log.txt MediaStore API (Downloads 쓰기 + Termux 접근)
- Worker 로그 liveLog→fileLog 전환 (파일별 줄 교체)
- failedFolders 캐시 (실패 폴더 반복 재시도 방지)
- skipOneDriveCheck 기본값 true
- 미사용 코드 정리 (updateLogCallback, workerLineIndex, .orig)
