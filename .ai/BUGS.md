# 버그 및 개선사항 (2026-03-13)

## 미해결

### P2 (중요)
- P2-5: 중단 버튼 즉시 중단 (코드 수정 완료, 실기기 테스트 필요)
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
