# 파일 인덱스

## ⚠ 소스 파일 규칙
1파일 300줄 이하. 초과 시 분할 필수.

## Kotlin 소스 (app/src/main/java/com/gphotosync/)

| 파일 | 줄수 | 역할 | 상태 |
|------|------|------|------|
| OneDriveApi.kt | 422 | MS Graph API | ⚠ 분할 필요 |
| SyncForegroundService.kt | 398 | 실시간 동기화 서비스 | ⚠ 분할 필요 |
| TakeoutUploadPipeline.kt | 382 | Producer/Worker 파이프라인 | ⚠ 분할 필요 |
| MainActivity.kt | 376 | 탭/라이프사이클/API 초기화 | ⚠ 분할 필요 |
| TakeoutUploadService.kt | 370 | 서비스 본체/상태저장/알림 | ⚠ 분할 필요 |
| TakeoutTabHelper.kt | 331 | Takeout 탭 UI 이벤트 | ⚠ 분할 필요 |
| TakeoutOperations.kt | 268 | analyzeZip/organizeAlbums/migrate | ✅ OK |
| TakeoutTabState.kt | 250 | Takeout 상태관리/콜백 | ✅ OK |
| SyncTabHelper.kt | 241 | 동기화 탭 UI | ✅ OK |
| OAuthActivity.kt | 210 | WebView OAuth | ✅ OK |
| GooglePhotosApi.kt | 190 | Google Photos Picker API | ✅ OK |
| TokenManager.kt | 180 | 토큰 암호화/갱신 | ✅ OK |
| SyncProgressStore.kt | 136 | 동기화 진행 저장 | ✅ OK |
| TakeoutUtils.kt | 117 | 데이터 클래스/유틸 | ✅ OK |
| OAuthCallbackActivity.kt | 27 | OAuth 콜백 (미사용) | ✅ OK |
| GPhotoSyncApp.kt | 10 | Application 클래스 | ✅ OK |
| **합계** | **3908** | 16개 파일 | |

## 최근 작업 (2026-03-13)
- ✅ TakeoutTabHelper.kt 분리 (557→331줄)
  - TakeoutTabHelper.kt (331줄): UI 이벤트 처리
  - TakeoutTabState.kt (250줄): 상태 관리/콜백

## 남은 작업
- OneDriveApi.kt (422줄) → 2~3개 파일 분리
- SyncForegroundService.kt (398줄) → 주석 제거로 300줄 이하
- TakeoutUploadPipeline.kt (382줄) → Producer/Worker 분리
- MainActivity.kt (376줄) → 주석 제거로 300줄 이하
- TakeoutUploadService.kt (370줄) → 주석 제거로 300줄 이하
- TakeoutTabHelper.kt (331줄) → 주석 제거로 300줄 이하

## GitHub raw URL
https://raw.githubusercontent.com/Station-Sage/GPhotoSync_Android/main/{경로}
