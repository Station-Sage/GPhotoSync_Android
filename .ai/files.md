# 소스 파일 인덱스

경로: app/src/main/java/com/gphotosync/

## 핵심 서비스
- OneDriveApi.kt (~190줄) — MS Graph API, suspend 함수, 폴더/파일/앨범 관리
- OneDriveUploader.kt (~130줄) — simple/chunked 업로드, ChunkSource 추상화
- TokenManager.kt (~150줄) — Google/Microsoft OAuth 토큰 저장/갱신

## Takeout 업로드
- TakeoutUploadService.kt (~250줄) — 서비스 정의, 콜백, 알림, 상태 관리
- TakeoutUploadPipeline.kt (~300줄) — ZIP 스트리밍, Channel(8) + Worker 3개 병렬
- TakeoutOperations.kt (~170줄) — 앨범 정리(createAlbum), 월→년 마이그레이션(moveFile)
- TakeoutTabHelper.kt (~200줄) — Takeout 탭 UI 이벤트, 버튼, 날짜 필터

## 실시간 동기화
- SyncForegroundService.kt (~340줄) — Google Photos → OneDrive 실시간 동기화

## UI
- MainActivity.kt (~400줄) — 4탭(Sync/Auth/Info/Takeout), 인증 플로우, 설정

## 앱 설정
- GPhotoSyncApp.kt (~10줄) — Application 클래스

## 규칙
- 1파일 300줄 이하 목표, 초과 시 분할 검토
