# GPhotoSync — Google Photos to OneDrive 자동 동기화 앱

> 최종 업데이트: 2026-03-11

---

## 1. 프로젝트 개요

GPhotoSync는 Google Photos에 저장된 사진과 동영상을 Microsoft OneDrive로 이동하는 Android 앱입니다. 두 가지 동기화 경로를 제공합니다.

**경로 A — 실시간 동기화 (Sync 탭)**: Google Photos Picker API로 사진을 선택하여 다운로드 후 OneDrive에 업로드합니다. 소규모(수백 장) 이동에 적합합니다.

**경로 B — Takeout 일괄 업로드 (Takeout 탭)**: Google Takeout에서 내보낸 ZIP 파일을 스트리밍 파싱하여 OneDrive에 업로드합니다. 대규모(수천~수만 장) 이동에 적합합니다.

---

## 2. 주요 기능

- 사진 + 동영상 모두 지원
- 앱 내 WebView OAuth (브라우저 이탈 없음)
- 백그라운드 동기화 (앱을 꺼도 계속 진행)
- 중단 후 재시작 시 이어서 진행
- 알림창에 실시간 진행 표시
- 토큰 암호화 저장 및 자동 갱신
- 4MB 이상 대용량 파일 청크 업로드 (10MB 단위)
- Takeout ZIP 스트리밍 파싱 (전체 압축 해제 없이 처리)
- 3개 Worker 병렬 업로드
- OneDrive Bundle API로 앨범 생성 (추가 용량 0)
- 월 폴더에서 연도 폴더로 마이그레이션
- 이어하기 시 OneDrive 실제 파일 존재 여부 확인
- 날짜 범위 필터 (시작일~종료일)
- MS 인증 만료 시 사전 검증 및 알림 다이얼로그
- 앱 전환 후 복귀 시 UI 상태 완전 복원 (탭 위치, 프로그레스바, 버튼, 로그)
- 재분석 버튼으로 ZIP 재분석 지원

---

## 3. OneDrive 최종 저장 구조

모든 파일은 연도 폴더에 1벌만 저장됩니다 (용량 1x).

    OneDrive/Pictures/GooglePhotos/
      2019/
        photo1.jpg
        video1.mp4
      2020/
        photo2.jpg
      2024/
        beach.jpg
        sunset.mp4

앨범은 OneDrive Bundle로 생성되며 추가 용량이 없습니다 (참조만).

    여행 2023 → beach.jpg, sunset.mp4, ...
    가족 모임 → photo1.jpg, photo2.jpg, ...

---

## 4. 앱 구조

### 소스 파일 (app/src/main/java/com/gphotosync/)

- **MainActivity.kt** — 탭 기반 메인 UI (동기화, 인증, 정보, Takeout), 탭 위치 저장/복원
- **SyncTabHelper.kt** — Sync 탭 UI 로직
- **TakeoutTabHelper.kt** — Takeout 탭 UI 로직 (ZIP 선택, 분석, 업로드, 앨범정리, 마이그레이션, 3상태 UI 관리)
- **SyncForegroundService.kt** — 경로 A 백그라운드 서비스 (Google → OneDrive 실시간)
- **TakeoutUploadService.kt** — 경로 B 백그라운드 서비스 (ZIP → OneDrive 일괄)
- **GooglePhotosApi.kt** — Google Photos Picker API 클라이언트
- **OneDriveApi.kt** — Microsoft Graph API 클라이언트
- **TokenManager.kt** — OAuth 토큰 암호화 저장 및 자동 갱신
- **SyncProgressStore.kt** — 동기화 진행 상황 DB (SQLite)
- **OAuthActivity.kt** — 인앱 WebView OAuth 화면
- **OAuthCallbackActivity.kt** — 딥링크 OAuth 콜백 수신

### 레이아웃 파일 (app/src/main/res/layout/)

- **activity_main.xml** — 메인 (TabLayout + FrameLayout)
- **activity_oauth.xml** — OAuth WebView
- **tab_sync.xml** — Sync 탭
- **tab_auth.xml** — 인증 탭
- **tab_info.xml** — 정보/설정 탭
- **tab_takeout.xml** — Takeout 탭

---

## 5. 기능 상세

### 5.1 실시간 동기화 (SyncForegroundService)

Google Photos Picker API를 사용하여 사용자가 선택한 사진을 OneDrive에 업로드합니다.

**흐름**: Picker 세션 생성 → 웹 UI에서 사진 선택 → 세션 폴링(3초 간격) → 선택된 미디어 목록 조회 → 각 항목 다운로드 → OneDrive 업로드

suspendCoroutine으로 모든 비동기 콜백을 코루틴 suspend 함수로 변환하여 busy-wait delay를 전면 제거했습니다. createdFolders 캐시로 중복 ensureFolder HTTP 호출을 방지합니다. 실패한 항목은 SyncProgressStore에 기록되며 재시도 버튼으로 재처리할 수 있습니다.

### 5.2 Takeout 일괄 업로드 (TakeoutUploadService)

Google Takeout ZIP 파일을 스트리밍으로 읽어 OneDrive에 병렬 업로드합니다.

**흐름**: ZIP 분석(JSON 메타데이터 + 미디어 목록 수집) → 미디어 파일 스트리밍 → Channel(버퍼 8) → Worker 3개 병렬 업로드

**ZIP 분석**: ZipArchiveInputStream으로 스트리밍 파싱합니다. JSON 메타데이터에서 촬영 날짜와 제목을 추출하여 ConcurrentHashMap에 매핑합니다. Takeout 폴더 구조에서 앨범명을 자동 인식합니다. 50개마다 상태를 저장하여 중단 후 이어하기가 가능합니다.

**업로드 파이프라인**: Producer 코루틴이 ZIP에서 미디어를 읽어 UploadItem을 생성합니다. 4MB 이하는 메모리에, 초과는 임시 파일에 스풀링합니다. Worker 3개가 Channel에서 소비하여 병렬 업로드합니다. 폴더 캐시(부분 경로 포함)로 HTTP 호출을 최소화하고, 업로드 성공 시 driveItem ID를 저장합니다.

**MS 인증 사전 검증**: 업로드 시작 전 TokenManager.getValidMicrosoftToken으로 토큰 유효성을 확인합니다. 만료 시 AlertDialog로 인증 탭 이동을 안내합니다. OneDriveApi의 기존 OkHttpClient를 재사용하여 토큰 갱신의 일관성을 보장합니다.

**이어하기**: SharedPreferences에 완료 파일명을 저장합니다. 이어하기 시 checkFileExists로 OneDrive에 실제 존재하는지 확인하고, 삭제된 파일은 재업로드합니다.

### 5.3 앨범 정리 (organizeAlbums)

Takeout ZIP의 앨범 정보로 OneDrive에 앨범을 생성합니다. OneDrive Bundle API(POST /drive/bundles)를 사용하며 용량은 0입니다. 150개씩 분할 생성하고, 동일 이름 앨범이 있으면 children만 추가하여 중복을 방지합니다.

### 5.4 월 폴더 마이그레이션 (migrateMonthToYear)

이전 버전의 연도/월 구조를 연도 구조로 정리합니다. PATCH parentReference로 파일을 이동하고, 빈 월 폴더는 자동 삭제합니다.

---

## 6. OneDrive API (OneDriveApi.kt)

Microsoft Graph API v1.0 기반. OkHttp 사용. 타임아웃: connect 30s, read 300s, write 120s.

- **ensureFolder** — 폴더 경로 재귀 생성 (POST /me/drive/{parent}/children, conflictBehavior: fail), Mutex + 3회 재시도(2s/4s/6s)로 경쟁 조건 방지
- **uploadFile** — 파일 업로드 후 driveItem ID 반환 (4MB 이하: PUT, 초과: Upload Session + 10MB 청크)
- **checkFileExists** — 파일 존재 여부 + 크기 확인
- **copyFile** — 파일 복사
- **getItemId** — 경로로 driveItem ID 조회
- **createAlbum** — Bundle(앨범) 생성 (conflictBehavior: fail, 중복 앨범 검사)
- **moveFile** — 파일을 다른 폴더로 이동 (PATCH parentReference)
- **listChildren** — 폴더 내 자식 목록 조회
- **deleteItem** — 항목 삭제
- **getFolderId** — 폴더 경로로 ID 조회

---

## 7. Google Photos API (GooglePhotosApi.kt)

Google Photos Picker API 기반.

- **createSession** — Picker 세션 생성 (sessionId + pickerUri)
- **pollSession** — 세션 상태 폴링
- **listPickedMedia** — 선택된 미디어 목록 페이징 조회
- **downloadMedia** — 미디어 다운로드 (사진: =d, 동영상: =dv)
- **deleteSession** — 세션 삭제

---

## 8. 토큰 관리 (TokenManager.kt)

EncryptedSharedPreferences(AES-256-GCM)로 토큰을 암호화 저장합니다. 실패 시 일반 SharedPreferences로 폴백합니다. 만료 5분 전 자동으로 refresh_token을 교환합니다. 인증 탭에서 JSON 파일로 인증 정보를 내보내기/가져오기할 수 있습니다.

---

## 9. UI 구조 (4개 탭)

**탭 0 — 동기화**: 실시간 Google Photos → OneDrive 동기화. 진행률, 실시간 로그, 실행 기록, 성공/실패 상세 내역 표시.

**탭 1 — 인증**: Google/Microsoft OAuth 설정 및 로그인. Client ID 직접 입력 또는 JSON 파일 가져오기 (RadioButton). 인증 정보 내보내기/가져오기 및 전체 초기화. JSON 파일 선택 시 라디오 버튼 상태를 수동 입력으로 유지하여 시각적 혼란을 방지합니다.

**탭 2 — 정보**: 테마 설정 (시스템, 라이트, 다크).

**탭 3 — Takeout**: Google Takeout ZIP → OneDrive 일괄 업로드.

Takeout 탭 버튼 목록:
- Google Takeout 열기 — Takeout 웹페이지 오픈
- ZIP 파일 선택 — ZIP 선택 후 자동 분석 시작
- 분석 중단 / 분석 이어하기 — 분석 중단 및 재개
- 날짜 범위 필터 — 시작일~종료일로 미디어 필터링
- OneDrive에 업로드 — 분석 완료 후 업로드 시작
- 이어하기 — 중단된 업로드 재개
- 재분석 — ZIP 파일을 처음부터 다시 분석
- 앨범 폴더 정리 — OneDrive Bundle 앨범 생성 (업로드 완료 후 표시)
- 월 폴더 → 연도 폴더 정리 — 기존 월 하위폴더를 연도 폴더로 이동 (업로드 완료 후 표시)

### Takeout 탭 UI 상태 관리

앱 전환(다른 앱 갔다 오기) 후에도 UI가 일관되게 표시됩니다. restoreState()가 서비스 상태를 확인하여 3가지 분기로 UI를 복원합니다.

**분석 중**: 프로그레스바, 분석 상태 텍스트, 중단 버튼 표시. 업로드/이어하기 버튼 비활성화.

**업로드 중**: 프로그레스바(진행률/용량/속도), 중단 버튼 표시. 업로드/이어하기/앨범/마이그레이션/재분석 버튼 숨김. applyUploadingUI() 공통 함수로 progressCallback과 restoreState()에서 동일한 UI를 렌더링합니다.

**대기 중 (ZIP 선택됨)**: 프로그레스바 숨김, 업로드 버튼 활성화, 이어하기(있으면) 표시, 앨범/마이그레이션/재분석 버튼 조건부 표시. applyIdleUI() 공통 함수 사용.

**업로드 완료**: applyFinishedUI()로 결과 요약(성공/스킵/실패), 앨범/마이그레이션 버튼 표시.

탭 위치는 SharedPreferences(app_settings/last_tab)에 저장되어 앱 복귀 시 마지막 탭으로 자동 이동합니다.

---

## 10. 동시성 및 스레드 안전성

- aDone, aErrors, aSkipped, aDoneBytes → AtomicInteger / AtomicLong
- uploaded (업로드 완료 파일 집합) → Collections.synchronizedSet
- createdFolders (폴더 캐시) → synchronized(lock) 블록
- addUploadedFile / flushUploadedFiles → @Synchronized
- liveLog → @Synchronized
- jsonDateMap → ConcurrentHashMap
- pendingUploaded → @Synchronized 메서드 내에서만 접근
- notifyProgress → @Synchronized + 300ms 쓰로틀
- progressCallback (UI 업데이트) → 500ms 쓰로틀
- OkHttp 콜백 → 코루틴 → suspendCoroutine (busy-wait delay 전면 제거)
- ensureFolder → Mutex + 3회 재시도 (2s/4s/6s)로 동시 폴더 생성 경쟁 조건 방지

---

## 11. 성능 최적화 내역

- Channel 버퍼 8로 Producer가 Worker보다 앞서 읽어 I/O 파이프라인 유지
- Worker 3개 병렬 업로드로 모바일 네트워크 최적화 (API 쓰로틀링 회피)
- 폴더 캐시에 부분 경로 포함 (Pictures, Pictures/GooglePhotos, Pictures/GooglePhotos/2024 모두 캐시)
- baos.reset() 즉시 호출로 대용량 파일의 이중 메모리 점유 방지
- OkHttp readTimeout 300s로 대용량 청크 업로드 타임아웃 방지
- 10MB 청크 업로드로 4MB 초과 파일 자동 분할
- notifyProgress 300ms 쓰로틀로 알림 시스템 과부하 방지
- progressCallback 500ms 쓰로틀로 UI 업데이트 빈도 제한
- delay(100)/delay(200) 전면 제거 후 suspendCoroutine으로 교체
- SyncForegroundService에 ensureFolder 캐시 추가로 매 파일 HTTP 호출을 1회로 축소

---

## 12. 데이터 저장소 (SharedPreferences)

- **gphotosync_tokens** — OAuth 토큰 (암호화)
- **takeout_analyze** — ZIP 분석 진행 상태 (sc, mc, ts, jc)
- **takeout_media_list** — 미디어 파일명 목록 (StringSet)
- **takeout_json_map** — JSON 메타데이터 맵 (파일명 → 연도)
- **takeout_progress** — 업로드 완료 파일 목록 (StringSet)
- **takeout_drive_ids** — 파일명 → driveItem ID
- **takeout_album_map** — ZIP 경로 → 앨범명
- **takeout_session** — 마지막 ZIP URI
- **takeout_organized** — 앨범 정리 완료 파일
- **app_settings** — 테마 설정 (0: 시스템, 1: 라이트, 2: 다크), 마지막 탭 위치 (last_tab)

---

## 13. APK 빌드 방법

### 방법 1: GitHub Actions 자동 빌드 (가장 쉬움)

1. GitHub에 코드 push
2. Actions 탭에서 자동 빌드 시작 (3~5분 소요)
3. Artifacts에서 GPhotoSync-debug-apk 다운로드
4. APK 파일 실행하여 설치

### 방법 2: Android Studio 로컬 빌드

1. Android Studio 설치
2. File → Open → 이 폴더 선택
3. Gradle Sync 완료 대기
4. Build → Build APK(s)
5. app/build/outputs/apk/debug/app-debug.apk 생성

### 방법 3: AI 에이전트 빌드 (Claude Code 연동)

ai-apply "커밋 메시지" 명령으로 코드 수정 후 자동 push 및 빌드를 실행합니다.

---

## 14. 앱 사용 방법

### 1단계: Google API 설정

1. https://console.cloud.google.com 접속
2. 새 프로젝트 생성
3. Photos Library API 활성화
4. OAuth 2.0 클라이언트 ID 생성 (데스크톱 앱 유형)
5. 리디렉션 URI 추가: gphotosync://oauth/callback
6. Client ID / Client Secret 복사

### 2단계: Microsoft API 설정

1. https://portal.azure.com 접속
2. 앱 등록 → 새 등록
3. 개인 Microsoft 계정 포함 선택
4. 리디렉션 URI: gphotosync://oauth/callback (모바일 및 데스크톱 클라이언트)
5. Application (Client) ID 복사
6. API 권한: Files.ReadWrite, offline_access 추가

### 3단계: 앱 실행

1. 앱 실행 → 인증 탭에서 API 설정 버튼으로 ID 입력
2. Google 로그인 → Microsoft 로그인 (인앱 WebView)
3. Sync 탭에서 동기화 시작 또는 Takeout 탭에서 ZIP 업로드

---

## 15. 버그 수정 이력

### 2026-03-11

#### TakeoutTabHelper.kt
- restoreState()를 3상태 분기로 리팩토링 (분석 중 / 업로드 중 / 대기 중)
- applyUploadingUI(), applyFinishedUI(), applyIdleUI() 공통 함수 추출
- progressCallback과 restoreState()에서 동일한 UI 렌더링 보장
- 로그 버퍼 복원 시 중복 제거 (clear 후 restore)
- btnStartTakeout visibility 누락 수정 (GONE 상태에서 복원되지 않던 버그)
- 업로드 중단 후 btnStartTakeout VISIBLE + isEnabled 설정 추가
- 업로드 완료 시 btnStartTakeout VISIBLE 설정 추가

#### TakeoutUploadService.kt
- MS 인증 사전 검증 추가 (업로드 시작 전 토큰 유효성 확인)
- 인증 만료 시 AlertDialog로 인증 탭 이동 안내
- getValidMicrosoftToken에 OneDriveApi의 기존 OkHttpClient 재사용
- currentProgress 업로드 시작 시 초기화 추가

#### MainActivity.kt
- 탭 위치 SharedPreferences 저장/복원 (앱 전환 후 마지막 탭 유지)
- 인증 탭 JSON 파일 선택 시 라디오 버튼 상태 수동 입력으로 유지 (UX 혼란 방지)

### 2026-03-10

#### TakeoutUploadService.kt
- done, errors 등을 AtomicInteger로 교체
- uploaded를 synchronizedSet으로 래핑
- createdFolders를 synchronized(lock) 보호
- liveLog, addUploadedFile, flushUploadedFiles에 @Synchronized 적용
- jsonDateMap을 ConcurrentHashMap으로 교체
- Producer 취소 시 tmpFile 누수 수정 (try-catch + delete)
- notifyProgress 300ms 쓰로틀 추가
- organizeAlbums busy-wait를 suspendCoroutine으로 교체
- baos.reset() 즉시 호출로 메모리 해제
- Channel 버퍼 3에서 8로 증가
- 부분 경로 캐시 (parts.take(i)) 추가
- progressCallback 500ms 쓰로틀 적용
- 연도/월에서 연도 전용 폴더로 변경 (yearOnly)
- uploadFileSuspend 반환값을 String?(driveItem ID)으로 변경
- saveDriveItemId로 업로드 시 ID 저장
- organizeAlbums를 Bundle API 앨범 생성으로 교체
- 이어하기 시 checkFileExists로 실제 존재 확인
- migrateMonthToYear 기능 추가
- substringBefore 데드코드 제거
- ensureFolder에 Mutex + 3회 재시도(2s/4s/6s) 로직 추가

#### OneDriveApi.kt
- readTimeout 60s에서 300s로 증가
- uploadFile 반환값을 Boolean에서 String?로 변경
- getItemId, createAlbum 메서드 추가
- addChildrenToBundleBatch에서 $ref를 POST /children으로 교체
- createAlbum 중복 앨범 검사 추가
- conflictBehavior를 rename에서 fail로 변경
- moveFile, listChildren, deleteItem, getFolderId 추가
- getItemId URL을 toHttpUrl() 빌더로 교체 (OkHttp 4 호환)

#### SyncForegroundService.kt
- uploadOk = ok를 uploadOk = ok != null로 수정
- uploadDone 누락을 suspendCoroutine으로 전면 교체
- extractYearMonth를 extractYear로 변경 (연도 전용)
- createdFolders 캐시 추가
- 모든 delay(100)/delay(200) busy-wait를 suspendCoroutine으로 교체

#### TakeoutTabHelper.kt 및 tab_takeout.xml
- 마이그레이션 버튼 (btnMigrateFolder) 핸들러 및 UI 추가

---

## 16. 알려진 제한사항

1. OneDrive 개인용 Bundle API는 정식 지원이지만 향후 API 변경 가능성이 존재합니다.
2. Google Photos Picker API는 세션당 선택 가능 사진 수에 제한이 있습니다. 대량 이동은 Takeout을 권장합니다.
3. Takeout ZIP 분석은 순차 스트리밍이므로 중단 시 처음부터 재탐색이 필요합니다 (이어하기는 카운터 기반 스킵).
4. listChildren은 top=1000으로 제한됩니다. 폴더 내 파일이 1000개를 초과하면 페이징이 필요합니다.
5. Bundle 앨범에 자식 추가 시 개별 POST를 사용합니다. 대형 앨범은 시간이 소요됩니다.
