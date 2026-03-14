# 환경 4: AI챗 + VS Code (Win10 랩탑)

## 개요
- 기기: Windows 10 랩탑 (2코어, 4GB RAM)
- 편집기: VS Code (Git 내장)
- 빌드: GitHub Actions (로컬 빌드 안함)
- AI 코드 전달: AI챗 → 복사 → VS Code에 붙여넣기
- 토큰 무제한 — 소스 읽기/출력 자유

## AI 답변 규칙
- 코드 수정: 파일별 코드 블록으로 제공, 파일 경로를 블록 위에 명시
- 여러 파일 수정: 파일별로 분리하여 순서대로 나열
- 전체 교체: 파일 전체 코드 제공 (300줄 이하), 사용자가 Ctrl+A → Ctrl+V → Ctrl+S
- 부분 수정: "N번째 줄 ~ M번째 줄을 아래로 교체" 형식으로 안내
- 결과 확인 명령 안내 시 항상 | ai-copy 포함
- 토큰 무제한이므로 설명, 전체 파일 출력 가능

## 초기 설정 (1회)

### 1. Git 설치
https://gitforwindows.org/ 에서 다운로드, 설치 시 Git Bash 포함 선택

### 2. VS Code 설치
https://code.visualstudio.com/ 에서 다운로드, 확장 최소화 (성능)

### 3. 레포 클론
VS Code 터미널 (Git Bash) 에서:

    git clone https://github.com/Station-Sage/GPhotoSync_Android.git
    cd GPhotoSync_Android

### 4. ai-copy 스크립트 (Git Bash용)

    mkdir -p ~/bin
    echo '#!/bin/bash' > ~/bin/ai-copy
    echo 'cat | clip.exe' >> ~/bin/ai-copy
    chmod +x ~/bin/ai-copy

~/.bashrc에 추가: export PATH="$HOME/bin:$PATH"

## 작업 흐름

### AI챗 → VS Code (코드 적용)
1. AI챗에서 코드 블록 "Copy" 버튼 클릭
2. VS Code에서 대상 파일 열기 (Ctrl+P → 파일명)
3. Ctrl+A → Ctrl+V → Ctrl+S (전체 교체)
4. 여러 파일이면 2~3 반복

### VS Code → AI챗 (결과 전달)
방법 1 — 파일 내용:

    cat app/src/main/java/com/gphotosync/OneDriveApi.kt | ai-copy

AI챗에 Ctrl+V

방법 2 — 빌드 에러:

    # 에러 수
    ./gradlew assembleDebug 2>&1 | grep "^e:" | wc -l | ai-copy

    # 에러 상세
    ./gradlew assembleDebug 2>&1 | grep "^e:" | ai-copy

    # 파일별 에러 수
    ./gradlew assembleDebug 2>&1 | grep "^e:" | sed 's/.*gphotosync\///' | cut -d: -f1 | sort | uniq -c | sort -rn | ai-copy

방법 3 — 짧은 내용: VS Code에서 드래그 선택 → Ctrl+C → AI챗에 Ctrl+V

### 커밋 & 푸시
VS Code 터미널에서:

    git add -A && git commit -m "설명" && git push

또는 VS Code Source Control 패널 (Ctrl+Shift+G) → Stage All → 메시지 입력 → Commit → Push

### 빌드 확인
git push 후 GitHub Actions 자동 트리거 → GitHub 레포 Actions 탭에서 결과 확인 → APK Artifacts 다운로드

## 주의사항
- 로컬 빌드 없음 (gradle 미설치, 빌드는 GitHub Actions에서만)
- 빌드 에러 확인: GitHub Actions 로그에서 확인 후 AI챗에 붙여넣기
- RAM 부족 시: VS Code 확장 최소화, 터미널 하나만 사용
