# 알려진 버그 및 개선사항

## 미수정 버그
1. **대용량 동영상 업로드 중 앱 멈춤/끊김** — 청크 업로드 실패 시 재시도 없이 바로 실패 처리, Worker 블록 시 전체 멈춤
2. **인증 탭 토큰 만료 미반영** — isMicrosoftAuthed()가 토큰 존재만 확인, 만료 여부 미확인하여 항상 "인증 완료" 표시
3. **MS 재인증 후 업로드 자동 재시작 안 됨** — 수동으로 업로드 버튼 다시 클릭 필요
4. **listChildren top=1000 페이징 미구현** — 폴더 내 파일 1000개 초과 시 누락
5. **sync_log.txt 쓰기 퍼미션 문제** — logToFile 작동 안 됨, 디버깅 로그 저장 불가
6. **취소 후 프로그레스바 리셋 미확인**
7. **알림창 진행 상태와 앱 내 프로그레스 불일치** — notifyProgress(300ms)와 progressCallback(500ms) 쓰로틀 차이

## 기능 개선 대기
1. **업로드 중 MS 토큰 만료 처리** — 현재 시작 전에만 검증, 업로드 중간 만료 시 정지 후 재개 미지원
2. **오류 발생 시 알림 채널 활용 개선**
3. **대형 Bundle 앨범 성능** — 개별 POST → 배치 처리
4. **스킵 최적화** — 이어하기 시 로컬 SharedPreferences만으로 스킵 판단 (OneDrive HTTP 호출 제거)
5. **토큰 손실 원인 조사** — 앱 사용 중 토큰 사라지는 케이스 확인

## 수정 완료 (2026-03-11)
- restoreState() 3상태 분기 통합 → UI 일관성 확보
- btnStartTakeout visibility 누락 → VISIBLE 복원
- 로그 버퍼 중복 복원 → clear 후 restore
- 탭 위치 저장/복원 → 앱 전환 후 마지막 탭 유지
- folderMutex → 폴더별 락 → 병렬성 향상
- ZIP 파일 비교 URI→파일명 → 분석 결과 보존
- MS 토큰 검증 OkHttpClient 불일치 → api.client 재사용
- 속도 표시 0.0MB/s → actualUploadStartTime 기준
