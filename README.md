# GPhotoSync - Google Photos → OneDrive 자동 동기화 앱

## 📱 APK 빌드 방법 (GitHub Actions 자동 빌드 — PC 불필요!)

### ✅ 방법 1: GitHub Actions 자동 빌드 (가장 쉬움 — 5분)

1. **GitHub 계정 만들기** (없다면): https://github.com/join

2. **새 Repository 생성**:
   - https://github.com/new 접속
   - Repository 이름: `GPhotoSync`
   - Private 선택 (보안)
   - Create repository 클릭

3. **이 폴더 전체를 GitHub에 업로드**:
   ```bash
   cd GPhotoSync
   git init
   git add .
   git commit -m "Initial commit"
   git branch -M main
   git remote add origin https://github.com/본인아이디/GPhotoSync.git
   git push -u origin main
   ```
   > 모바일에서: GitHub 앱이나 웹에서 파일 업로드 가능

4. **Actions 탭 → 자동 빌드 시작** (3~5분 소요)

5. **APK 다운로드**:
   - Actions → 최신 워크플로우 → Artifacts → `GPhotoSync-debug-apk` 클릭

6. **설치**:
   - 다운로드한 APK 파일 실행
   - "알 수 없는 출처" 허용 후 설치

---

### ✅ 방법 2: Android Studio 로컬 빌드

1. **Android Studio** 설치: https://developer.android.com/studio
2. `File → Open` → 이 폴더 선택
3. Gradle Sync 완료 대기
4. `Build → Build Bundle(s)/APK(s) → Build APK(s)`
5. `app/build/outputs/apk/debug/app-debug.apk` 완성

---

## 🔑 앱 사용 방법

### 1단계: Google API 설정
1. https://console.cloud.google.com 접속
2. 새 프로젝트 생성
3. **Photos Library API** 활성화
4. OAuth 2.0 클라이언트 ID 생성 (데스크톱 앱 유형)
5. 리디렉션 URI 추가: `gphotosync://oauth/callback`
6. Client ID / Client Secret 복사

### 2단계: Microsoft API 설정
1. https://portal.azure.com 접속
2. **앱 등록** → 새 등록
3. 개인 Microsoft 계정 포함 선택
4. 리디렉션 URI: `gphotosync://oauth/callback` (모바일 및 데스크톱 클라이언트)
5. Application (Client) ID 복사
6. API 권한: `Files.ReadWrite`, `offline_access` 추가

### 3단계: 앱 실행
1. 앱 실행 → "API 설정" 버튼으로 ID 입력
2. Google 로그인 → Microsoft 로그인 (앱 내 WebView로 처리, 브라우저 이탈 없음!)
3. "동기화 시작" 버튼 클릭
4. 백그라운드에서 자동으로 모든 사진/동영상 이동!

---

## 📁 OneDrive 저장 구조
```
OneDrive/
└── Pictures/
    └── GooglePhotos/
        ├── 2022-03/  (년-월 별 자동 분류)
        ├── 2023-07/
        └── 2024-12/
```

---

## ⚙️ 기능
- ✅ 사진 + 동영상 모두 지원
- ✅ 앱 내 WebView OAuth (브라우저 이탈 없음)
- ✅ 백그라운드 동기화 (앱 꺼도 계속)
- ✅ 중단 후 재시작 시 이어서 진행
- ✅ 알림창에 실시간 진행 표시
- ✅ 토큰 암호화 저장 및 자동 갱신
- ✅ 4MB 이상 대용량 파일 청크 업로드

---

## 📦 프로젝트 구조
```
GPhotoSync/
├── .github/workflows/build.yml     ← GitHub Actions 자동 빌드
├── app/src/main/
│   ├── java/com/gphotosync/
│   │   ├── MainActivity.kt         ← 메인 UI
│   │   ├── OAuthActivity.kt        ← 인앱 OAuth WebView
│   │   ├── OAuthCallbackActivity.kt← 딥링크 콜백
│   │   ├── SyncForegroundService.kt← 백그라운드 동기화 엔진
│   │   ├── GooglePhotosApi.kt      ← Google Photos API
│   │   ├── OneDriveApi.kt          ← OneDrive Graph API
│   │   ├── TokenManager.kt         ← 토큰 암호화 관리
│   │   └── SyncProgressStore.kt   ← 진행 상황 저장
│   ├── res/layout/
│   │   ├── activity_main.xml       ← 메인 화면 레이아웃
│   │   └── activity_oauth.xml      ← 인증 화면 레이아웃
│   └── AndroidManifest.xml
└── build.gradle
```
