# AGENTS.md — AI Agent 컨텍스트

## 프로젝트
GPhotoSync — Google Takeout ZIP → OneDrive 업로드 Android 앱 (Kotlin)

## 빌드
- 빌드: `./gradlew assembleDebug`
- APK: `app/build/outputs/apk/debug/app-debug.apk`
- 설치: `cp app/build/outputs/apk/debug/app-debug.apk ~/storage/downloads/gphotosync.apk`

## 소스 경로
`app/src/main/java/com/gphotosync/`

## 주요 파일
- `OneDriveApi.kt` — MS Graph API (suspend, OkHttp)
- `OneDriveUploader.kt` — 업로드 (simple/chunked)
- `TakeoutUploadService.kt` — Takeout ZIP 업로드 서비스
- `TakeoutUploadPipeline.kt` — 업로드 파이프라인 (Producer + 3 Worker)
- `TakeoutOperations.kt` — 앨범 정리, 월→년 마이그레이션
- `SyncForegroundService.kt` — 실시간 동기화 서비스
- `TokenManager.kt` — OAuth 토큰 관리 (Google/Microsoft)

## AI 작업 가이드
- 세션 시작 시: `.ai/index.md` → `BUGS.md` + `.ai/todo.md` 순서로 읽기
- 코드 수정 전: `.ai/files.md`에서 파일 역할 확인
- 설계 판단 시: `.ai/decisions.md` 참조
- Termux + AI챗 환경: `.ai/env-termux-chat.md` 필독

## 핵심 규칙
- 1파일 300줄 이하, 초과 시 분할
- OneDriveApi는 suspend 함수 (callback 금지)
- 앨범 API: `/me/drive/bundles/` 경로 사용
