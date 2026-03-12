# 현재 작업 (2026-03-12)

## 상태: 버그 수정 + 코드 정리 완료 — 실기기 테스트 중

## 오늘 완료 (03-12)
- [x] sync_log.txt MediaStore API로 변경 (Downloads 직접 쓰기, Termux 접근 가능) — P3-8 해결
- [x] Worker 로그 fileLog 전환 (파일별 줄 교체: 업로드중→완료 같은 줄, 다음 파일 새 줄)
- [x] failedFolders 캐시 추가 (실패 폴더 반복 재시도 방지)
- [x] skipOneDriveCheck 기본값 true (스킵 속도 대폭 개선)
- [x] 미사용 코드 정리 (updateWorkerLog, updateLogCallback, workerLineIndex, .orig)
- [x] MS 토큰 갱신 시 client_secret 빈 값 전송 제거 (공개 클라이언트 호환)
- [x] 토큰 refresh 마진 300초→60초 (유효 토큰으로 불필요 refresh 방지)

## 이전 완료 (03-11)
- [x] P1-1~P1-4: UI 복원, skipOneDriveCheck, 청크 재시도, OOM 방지
- [x] TakeoutUploadService.kt 4파일 분리 (1030→4파일)

## 검증 필요 (실기기)
- [x] sync_log.txt Termux에서 읽기 ✅
- [ ] 토큰 refresh 마진 60초로 변경 후 업로드 정상 동작 확인
- [ ] Worker 로그 파일별 줄 교체 동작 확인
- [ ] 이어하기 스킵 속도 개선 확인
- [ ] 폴더 생성 실패 시 failedFolders로 즉시 스킵 확인

## 다음 작업
- P2-6: 업로드 중 토큰 만료 시 즉시 중단 + 재인증 팝업
- P2-8: Azure 공개 클라이언트 설정 문제 해결/안내
- 나머지 P2 버그 수정 (BUGS.md 참고)
