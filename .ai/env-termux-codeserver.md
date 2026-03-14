# 환경 5: AI챗 + Termux code-server (갤럭시탭 S8 울트라)

## 개요
- 기기: 갤럭시탭 S8 울트라 (Snapdragon 8 Gen 1, 8~16GB RAM)
- 편집기: code-server (브라우저 VS Code)
- 터미널: Termux (code-server 내 터미널 = Termux 셸)
- 빌드: GitHub Actions (로컬 빌드도 가능)
- 권장: Samsung DeX 모드 + 외장 키보드
- 토큰 무제한 — 소스 읽기/출력 자유

## AI 답변 규칙
- 코드 수정: 파일별 코드 블록으로 제공, 파일 경로를 블록 위에 명시
- 여러 파일 수정: 파일별로 분리하여 순서대로 나열
- 전체 교체: 파일 전체 코드 제공 (300줄 이하), 사용자가 Ctrl+A → Ctrl+V → Ctrl+S
- 부분 수정: "N번째 줄 ~ M번째 줄을 아래로 교체" 형식으로 안내
- 결과 확인 명령 안내 시 항상 | ai-copy 포함
- 토큰 무제한이므로 설명, 전체 파일 출력 가능
- (환경4 랩탑과 동일한 규칙)

## 초기 설정 (1회)

### 1. Termux 설치
F-Droid 또는 GitHub에서 Termux 최신 버전 설치
https://github.com/termux/termux-app/releases

### 2. Termux 기본 패키지

    pkg update && pkg upgrade
    pkg install git nodejs-lts

### 3. code-server 설치

    pkg install tur-repo
    pkg install code-server

### 4. code-server 실행

    code-server --bind-addr 0.0.0.0:8080 --auth none

브라우저에서 http://localhost:8080 접속

### 5. 레포 클론
code-server 터미널에서:

    cd ~
    git clone https://github.com/Station-Sage/GPhotoSync_Android.git
    cd GPhotoSync_Android

### 6. ai-copy 스크립트
기존 Termux 스크립트 그대로 사용 (ai-copy = cat | termux-clipboard-set)
Termux:API 앱 필요: pkg install termux-api
setup-termux.sh 실행으로 ~/bin에 스크립트 심볼릭 링크 생성

## 작업 흐름

### AI챗 → code-server (코드 적용)
1. AI챗에서 코드 블록 "Copy" 버튼 클릭
2. code-server에서 파일 열기 (Ctrl+P → 파일명)
3. Ctrl+A → Ctrl+V → Ctrl+S (전체 교체)
4. DeX 분할 화면: 왼쪽 AI챗 + 오른쪽 code-server

### code-server → AI챗 (결과 전달)
방법 1 — 파일 내용:

    cat app/src/main/java/com/gphotosync/OneDriveApi.kt | ai-copy

AI챗에 길게 눌러 붙여넣기

방법 2 — 빌드 에러:

    ./gradlew assembleDebug 2>&1 | grep "^e:" | wc -l | ai-copy
    ./gradlew assembleDebug 2>&1 | grep "^e:" | ai-copy

방법 3 — code-server 에디터에서 Ctrl+A → Ctrl+C → AI챗에 붙여넣기

### 커밋 & 푸시
code-server 터미널에서:

    git add -A && git commit -m "설명" && git push

### 빌드
- GitHub Actions: git push 후 자동 트리거, Actions 탭에서 APK 다운로드
- 로컬 빌드 (가능): ./gradlew assembleDebug → cp 명령으로 APK 설치

## 환경 3 (스마트폰 Termux)과의 차이
| 항목 | 환경3 스마트폰 | 환경5 갤럭시탭 |
|------|---------------|---------------|
| 편집기 | ai-write (터미널 붙여넣기) | code-server (VS Code UI) |
| 파일 탐색 | sed -n, cat | VS Code 파일 트리 |
| 복사 | ai-copy (동일) | ai-copy (동일) |
| 화면 | 단일 앱 | DeX 분할 화면 |
| 편의성 | ★★★ | ★★★★★ |

## code-server 자동 시작 (선택)
~/.bashrc에 추가:

    alias cs='code-server --bind-addr 0.0.0.0:8080 --auth none ~/GPhotoSync_Android'

Termux 실행 후 cs 입력으로 바로 시작

## 주의사항
- code-server 터미널 = Termux 셸이므로 기존 ai-copy, ai-push 등 모든 스크립트 호환
- 외장 키보드 없으면 터치 키보드로도 가능하나 효율 크게 저하
- Termux 세션이 종료되면 code-server도 종료됨 (Termux 알림 고정 권장)
- code-server 메모리: 약 300~500MB 사용, 탭 S8 울트라에서 여유로움
