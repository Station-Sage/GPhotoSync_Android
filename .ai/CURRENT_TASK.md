# 현재 작업 (2026-03-13)

## 할 일: 소스코드 300줄 기준 분할 + .ai md 구조 개편

## 분할 대상
- TakeoutTabHelper.kt (562줄) → 3파일
- OneDriveApi.kt (422줄) → 3파일
- TakeoutUploadPipeline.kt (357줄) → 2파일
- MainActivity.kt (376줄) → 2파일
- SyncForegroundService.kt (398줄) → 2파일

## 읽어야 할 파일
- FILE_INDEX.md (분할 계획 참조)

## 미해결 버그
- P2-8: Azure 공개 클라이언트 토큰 refresh (invalid_client)
- P2-5: 중단 버튼 즉시 중단 (테스트 필요)
- P3-11: 취소 후 진행바 리셋 (테스트 필요)
- P3-12: 대용량 번들 앨범 성능

## 완료 항목 (03-13)
- 대용량 파일 스트리밍 업로드 (862MB 성공)
- P2-6 토큰 만료 5회 연속 실패 → 즉시 중단
- OneDriveApi.kt 재작성 (643→422줄)
- 전체 8261개 업로드 완료
