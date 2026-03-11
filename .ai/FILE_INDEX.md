# 파일 인덱스

## Kotlin 소스 (app/src/main/java/com/gphotosync/)
| 파일 | 줄수 | 역할 | 수정빈도 |
|------|------|------|----------|
| TakeoutUploadService.kt | ~1030 | 핵심: ZIP 분석, 업로드, 폴더 생성 | 높음 |
| TakeoutTabHelper.kt | ~550 | Takeout 탭 UI, 상태관리, 공통함수 | 높음 |
| OneDriveApi.kt | ~525 | MS Graph API (폴더/파일/청크/번들) | 중간 |
| MainActivity.kt | ~340 | 탭 관리, API 설정, 인증 백업 | 중간 |
| OAuthActivity.kt | ~210 | WebView OAuth (Google/MS) | 낮음 |
| TokenManager.kt | ~172 | 토큰 암호화 저장, 자동 갱신 | 낮음 |
| SyncForegroundService.kt | ~398 | 실시간 동기화 서비스 | 낮음 |
| SyncTabHelper.kt | ~241 | 동기화 탭 UI | 낮음 |
| GooglePhotosApi.kt | ~190 | Google Photos Picker API | 낮음 |
| SyncProgressStore.kt | ~136 | 동기화 진행 저장 | 낮음 |
| GPhotoSyncApp.kt | ~10 | Application 클래스 | 없음 |
| OAuthCallbackActivity.kt | ~27 | OAuth 콜백 (미사용) | 없음 |

## 레이아웃 XML
| 파일 | 역할 |
|------|------|
| activity_main.xml | 4탭 메인 UI |
| activity_oauth.xml | WebView OAuth |
| tab_takeout.xml | Takeout 탭 (버튼, 프로그레스, 로그) |
| tab_sync.xml | 동기화 탭 |
| tab_auth.xml | 인증 탭 |
| tab_info.xml | 정보 탭 |

## AI 컨텍스트 (.ai/)
PROJECT.md, ARCHITECTURE.md, FILE_INDEX.md, CURRENT_TASK.md, BUGS.md, DECISIONS.md, SCRIPTS.md, PROMPT_TEMPLATE.md
