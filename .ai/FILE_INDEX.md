# 파일 인덱스

## 소스 파일 규칙
1파일 300줄 이하. 초과 시 분할 필수.

## Kotlin 소스 (app/src/main/java/com/gphotosync/)

### 높은 수정빈도
| 파일 | 줄수 | 역할 | 상태 |
|------|------|------|------|
| TakeoutTabHelper.kt | 562 | Takeout 탭 UI/상태/콜백 | ⚠ 분할 필요 |
| TakeoutUploadPipeline.kt | 357 | Producer/Worker 파이프라인 | ⚠ 분할 필요 |

### 중간 수정빈도
| 파일 | 줄수 | 역할 | 상태 |
|------|------|------|------|
| OneDriveApi.kt | 422 | MS Graph API 전체 | ⚠ 분할 필요 |
| MainActivity.kt | 376 | 탭/라이프사이클/API 초기화 | ⚠ 분할 필요 |
| TakeoutUploadService.kt | 340 | 서비스 본체/상태저장/알림/로그 | ⚠ 분할 검토 |
| TakeoutOperations.kt | 268 | analyzeZip/organizeAlbums/migrate | ✅ OK |
| TokenManager.kt | 188 | 토큰 암호화 저장/자동 갱신 | ✅ OK |

### 낮은 수정빈도
| 파일 | 줄수 | 역할 |
|------|------|------|
| SyncForegroundService.kt | 398 | 실시간 동기화 서비스 (⚠ 분할 필요) |
| SyncTabHelper.kt | 241 | 동기화 탭 UI |
| OAuthActivity.kt | 212 | WebView OAuth |
| GooglePhotosApi.kt | 190 | Google Photos Picker API |
| SyncProgressStore.kt | 136 | 동기화 진행 저장 |
| TakeoutUtils.kt | 117 | 데이터 클래스/유틸 |
| OAuthCallbackActivity.kt | 27 | OAuth 콜백 (미사용) |
| GPhotoSyncApp.kt | 10 | Application 클래스 |

## 함수 인덱스 (주요 파일)

### TakeoutUploadPipeline.kt
- startUpload(): 18행 — 메인 파이프라인 진입
  - JSON 수집: 25~42행
  - 미디어 목록: 44~63행
  - MS 인증 확인: 72~90행
  - Producer (ZIP→Channel): 130~230행
  - Worker (업로드): 246~345행
  - 토큰 만료 감지: 330~343행

### TakeoutUploadService.kt
- companion object: 16~48행
- suspend 래퍼: 62~90행
- onStartCommand: 95~130행
- 상태 저장/로드: 133~230행
- 로그 (liveLog/fileLog): 260~300행
- 알림: 305~340행

### OneDriveApi.kt
- ensureFolder/createFolderChain: 34~62행
- uploadFile/simpleUpload/chunkedUpload: 64~140행
- uploadFileFromFile/uploadChunksFromFile: 142~210행
- checkFileExists: 212~230행
- copyFile/getItemId: 232~280행
- createAlbum/addChildrenToBundleBatch: 282~370행
- moveFile/listChildren/deleteItem/getFolderId: 372~422행

## 분할 계획

### TakeoutTabHelper.kt (562줄 → 3파일)
- TakeoutTabSetup.kt (~200줄): UI 초기화, 버튼 리스너, 날짜 필터
- TakeoutTabCallbacks.kt (~200줄): 서비스 콜백, 상태 복원
- TakeoutTabUI.kt (~160줄): applyUploadingUI/applyFinishedUI/applyIdleUI

### OneDriveApi.kt (422줄 → 3파일)
- OneDriveApi.kt (~200줄): 폴더/파일 업로드 (기본)
- OneDriveApiStream.kt (~100줄): File 기반 스트리밍 업로드
- OneDriveApiExtra.kt (~120줄): 앨범/번들, 복사/이동/삭제

### TakeoutUploadPipeline.kt (357줄 → 2파일)
- TakeoutProducer.kt (~180줄): ZIP 스트리밍 → Channel
- TakeoutWorker.kt (~180줄): Worker 업로드/에러/토큰 감지

### MainActivity.kt (376줄 → 2파일)
- MainActivity.kt (~200줄): 탭/라이프사이클
- MainActivitySetup.kt (~170줄): API 초기화, 인증 백업

## 레이아웃 XML
activity_main.xml, activity_oauth.xml, tab_takeout.xml, tab_sync.xml, tab_auth.xml, tab_info.xml

## GitHub raw URL 패턴
https://raw.githubusercontent.com/Station-Sage/GPhotoSync_Android/main/{경로}
