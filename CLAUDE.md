# CLAUDE.md — AI 개발 컨텍스트

## 프로젝트
GPhotoSync — Google Photos to OneDrive 자동 동기화 Android 앱 (Kotlin)

## 빌드
- 빌드: ./gradlew assembleDebug
- APK 위치: app/build/outputs/apk/debug/app-debug.apk
- APK 복사: cp app/build/outputs/apk/debug/app-debug.apk ~/storage/downloads/gphotosync.apk

## 소스 경로
app/src/main/java/com/gphotosync/

## 주요 파일
- OneDriveApi.kt — MS Graph API (suspend 함수, OkHttp)
- OneDriveUploader.kt — 업로드 (simple/chunked)
- TakeoutUploadService.kt — Takeout ZIP → OneDrive 서비스
- TakeoutUploadPipeline.kt — 업로드 파이프라인 (Producer + 3 Worker)
- TakeoutOperations.kt — 앨범 정리, 마이그레이션
- SyncForegroundService.kt — 실시간 동기화 서비스
- TokenManager.kt — OAuth 토큰 관리

## 아키텍처
- OneDriveApi: suspend 함수 (suspendCancellableCoroutine + OkHttp async)
- 업로드: Channel(8) + Worker 3개 병렬
- 앨범: OneDrive Bundle API (POST /drive/bundles, POST /drive/bundles/{id}/children)
- 마이그레이션: moveFile (PATCH parentReference), 409=스킵

## 최근 리팩토링 (2026-03-14)
- OneDriveApi.kt: callback → suspend 전환
- OneDriveUploader.kt: 신규 (업로드 분리)
- OneDriveFiles.kt: 삭제 (OneDriveApi로 통합)
- TakeoutUploadService.kt: 11개 suspend 래퍼 직접 호출로 교체
- moveFile 409: 성공(스킵) 처리
- createAlbum batchSize: 150 → 20
- addChild URL: /me/drive/items/ → /me/drive/bundles/
- addChild: 재시도 3회 + 딜레이, 400 "already exists" 스킵
