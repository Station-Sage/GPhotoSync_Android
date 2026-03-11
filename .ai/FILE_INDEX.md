# 파일 인덱스

## ⚠ 파일 크기 규칙
소스코드 1파일 1000줄 미만 유지. 초과 시 파일 분리 필요.

## Kotlin 소스 (app/src/main/java/com/gphotosync/)
| 파일 | 줄수 | 역할 | 수정빈도 | 비고 |
|------|------|------|----------|------|
| TakeoutUploadService.kt | ~1030 | 핵심: ZIP 분석, 업로드, 폴더 생성 | 높음 | ⚠ 분리 필요 |
| TakeoutTabHelper.kt | ~560 | Takeout 탭 UI, 상태관리, 공통함수 | 높음 | |
| OneDriveApi.kt | ~530 | MS Graph API (폴더/파일/청크/번들) | 중간 | |
| MainActivity.kt | ~340 | 탭 관리, API 설정, 인증 백업 | 중간 | |
| OAuthActivity.kt | ~210 | WebView OAuth (Google/MS) | 낮음 | |
| TokenManager.kt | ~172 | 토큰 암호화 저장, 자동 갱신 | 낮음 | |
| SyncForegroundService.kt | ~398 | 실시간 동기화 서비스 | 낮음 | |
| SyncTabHelper.kt | ~241 | 동기화 탭 UI | 낮음 | |
| GooglePhotosApi.kt | ~190 | Google Photos Picker API | 낮음 | |
| SyncProgressStore.kt | ~136 | 동기화 진행 저장 | 낮음 | |
| GPhotoSyncApp.kt | ~10 | Application 클래스 | 없음 | |
| OAuthCallbackActivity.kt | ~27 | OAuth 콜백 (미사용) | 없음 | |

## 레이아웃 XML (app/src/main/res/layout/)
activity_main.xml, activity_oauth.xml, tab_takeout.xml, tab_sync.xml, tab_auth.xml, tab_info.xml

## AI 컨텍스트 (.ai/)
PROJECT.md, ARCHITECTURE.md, FILE_INDEX.md, CURRENT_TASK.md, BUGS.md, DECISIONS.md, SCRIPTS.md, PROMPT_TEMPLATE.md

## GitHub repo에서 소스 읽기
raw URL 형식: https://raw.githubusercontent.com/station-sage/GPhotoSync_Android/main/{파일경로}
