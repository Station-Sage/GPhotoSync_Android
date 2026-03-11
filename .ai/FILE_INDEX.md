# 파일 인덱스

## Kotlin 소스 (app/src/main/java/com/gphotosync/)
| 파일 | 줄 | 역할 | 수정빈도 |
|------|-----|------|----------|
| TakeoutUploadService.kt | 1026 | Takeout ZIP 분석/업로드 핵심 | 높음 |
| OneDriveApi.kt | 525 | Microsoft Graph API | 중간 |
| TakeoutTabHelper.kt | 518 | Takeout 탭 UI | 중간 |
| SyncForegroundService.kt | 398 | 실시간 동기화 서비스 | 낮음 |
| MainActivity.kt | 323 | 메인 (탭 관리) | 낮음 |
| SyncTabHelper.kt | 241 | 동기화 탭 UI | 낮음 |
| OAuthActivity.kt | 238 | OAuth WebView | 낮음 |
| GooglePhotosApi.kt | 190 | Google Photos Picker API | 낮음 |
| TokenManager.kt | 172 | 토큰 암호화 저장/갱신 | 낮음 |
| SyncProgressStore.kt | 136 | 동기화 기록 (SharedPrefs) | 낮음 |
| OAuthCallbackActivity.kt | 27 | OAuth 콜백 | 거의없음 |
| GPhotoSyncApp.kt | 10 | Application 초기화 | 거의없음 |

## 레이아웃 (app/src/main/res/layout/)
| 파일 | 설명 |
|------|------|
| activity_main.xml | 4탭 메인 |
| activity_oauth.xml | OAuth WebView |
| tab_takeout.xml | Takeout 탭 |
| tab_sync.xml | 동기화 탭 |
| tab_auth.xml | 인증 탭 |
| tab_info.xml | 정보/설정 탭 |

## AI 컨텍스트 (.ai/)
| 파일 | 용도 |
|------|------|
| PROJECT.md | 개발환경, 워크플로우, AI 규칙 |
| SCRIPTS.md | 스크립트 사용법, 작업 흐름 |
| ARCHITECTURE.md | 파이프라인, 동시성, 상수 |
| FILE_INDEX.md | 파일 목록 + 수정빈도 |
| CURRENT_TASK.md | 현재 작업 상태 |
| BUGS.md | 버그 트래커 |
| DECISIONS.md | 설계 결정 기록 |
| PROMPT_TEMPLATE.md | 새 채팅 시작 프롬프트 |
