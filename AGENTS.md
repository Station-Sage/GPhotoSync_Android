# AGENTS.md — AI Agent 컨텍스트

## GitHub
https://github.com/Station-Sage/GPhotoSync_Android

## 세션 시작
위 레포에서 아래 순서로 읽기:
1. .ai/index.md (라우터)
2. BUGS.md (버그 현황)
3. .ai/todo.md (할일)
Termux + AI챗 환경이면 .ai/env-termux-chat.md 추가로 읽기

## 프로젝트
GPhotoSync — Google Takeout ZIP → OneDrive 업로드 Android 앱 (Kotlin)

## 빌드
- 빌드: ./gradlew assembleDebug
- APK: app/build/outputs/apk/debug/app-debug.apk
- 설치: cp app/build/outputs/apk/debug/app-debug.apk ~/storage/downloads/gphotosync.apk

## 소스 경로
app/src/main/java/com/gphotosync/

## 주요 파일
- OneDriveApi.kt — MS Graph API (suspend, OkHttp)
- OneDriveUploader.kt — 업로드 (simple/chunked)
- TakeoutUploadService.kt — Takeout ZIP 업로드 서비스
- TakeoutUploadPipeline.kt — 업로드 파이프라인 (Producer + 3 Worker)
- TakeoutOperations.kt — 앨범 정리, 월→년 마이그레이션
- SyncForegroundService.kt — 실시간 동기화 서비스
- TokenManager.kt — OAuth 토큰 관리 (Google/Microsoft)

## 핵심 규칙
- 1파일 300줄 이하, 초과 시 분할
- OneDriveApi는 suspend 함수 (callback 금지)
- 앨범 API: /me/drive/bundles/ 경로 사용
