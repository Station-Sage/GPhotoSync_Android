# 현재 작업 (2026-03-13)

## 상태: Azure confidential client 전환 완료, 실기기 테스트 필요

## 오늘 완료 (03-13 오후 - Azure 전환)
- P2-7: 대용량 파일 OOM 해결 완료 (전체 업로드 완료 확인)
- P2-8: Azure confidential client 전환 작업
  - OAuthActivity.kt: 하드코딩된 client_secret 제거, TokenManager에서 가져오기
  - TokenManager.kt: MS refresh에서 client_secret 필수 전송으로 변경
  - 로그 추가: client_secret 없을 시 명확한 에러 메시지
  - 리디렉션 URI 확인: 이미 localhost 사용 중 (변경 불필요)

## 오늘 완료 (03-13 오후 - 버그 수정)
- P2-5: 중단 버튼 즉시 중단 개선 (stopSelf() 추가, 500ms delay 후 서비스 종료)
- P3-11: 취소 후 진행바 리셋 추가 (ProgressBar/TextView 숨김 처리)
- P3-10: listChildren 페이징 이미 구현 확인 (OneDriveApi.kt 366-387줄)
- BUGS.md 업데이트 (P2-5/P3-10/P3-11 완료 표시)

## 오늘 완료 (03-13 오전)
- OneDriveApi.kt 전체 재작성 (643줄→422줄, 구조 수정)
- uploadFileFromFile / uploadChunksFromFile 추가 (File 기반 10MB 청크 스트리밍)
- TakeoutUploadService.kt에 uploadFileFromFileSuspend 래퍼 추가
- TakeoutUploadPipeline.kt Worker에서 tmpFile 존재 시 스트리밍 업로드 분기

## 실기기 테스트 결과
- ✅ 862MB 영상 파일 스트리밍 업로드 성공 (21.8MB/s, OOM 방지)
- ✅ 52/191/275MB 대용량 파일 스트리밍 정상
- ✅ 이어하기 스킵 속도 정상
- ✅ fileLog 줄 교체 (⏳→✅ 같은 줄에서 교체)
- ✅ failedFolders 캐시 즉시 스킵
- ✅ 앱 전환 후 UI 복원 정상
- ✅ 분석 UI 100% 정상 완료 (P3-13 해결)
- ✅ 전체 8261개 업로드 완료
- ⏳ P2-5 중단 버튼 즉시 중단 테스트 필요
- ⏳ P3-11 진행바 리셋 테스트 필요
- ⏳ P2-8 Azure confidential client 토큰 갱신 테스트 필요

## 이전 완료 (03-12)
- sync_log.txt MediaStore API (P3-8 해결)
- Worker 로그 fileLog 전환
- failedFolders 캐시, skipOneDriveCheck=true
- 미사용 코드 제거, client_secret 조건부, refresh 마진 60초

## 다음 작업
- P2-8: 실기기에서 Azure confidential client 토큰 갱신 테스트
- P3-12: 대용량 번들 앨범 성능 개선 검토
- 전체 통합 테스트
