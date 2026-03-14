# .ai/index.md — AI 문서 라우터

## 세션 시작 시 읽기 (필수)
1. BUGS.md (루트) — 버그 현황, 미수정 항목 확인
2. .ai/todo.md — 오늘 할일, 진행 상태

## 작업별 참조 (필요 시만)
| 파일 | 언제 읽나 |
|------|----------|
| .ai/architecture.md | 앱 구조, 서비스 흐름 파악 시 |
| .ai/files.md | 소스 파일 역할, 수정 대상 파악 시 |
| .ai/decisions.md | 설계 판단, 과거 결정 이유 확인 시 |
| .ai/changelog.md | 최근 변경사항 확인 시 |
| .ai/env-termux-chat.md | 환경3: Termux + AI챗 (스마트폰) |
| .ai/env-vscode-laptop.md | 환경4: VS Code + AI챗 (Win10 랩탑) |
| .ai/env-termux-codeserver.md | 환경5: Termux code-server + AI챗 (갤럭시탭) |

## 작업 환경
| # | 환경 | 기기 | 별칭 | 빌드 | 가이드 |
|---|------|------|------|------|--------|
| 1 | Claude Code (웹/앱) + GitHub빌드 | any | — | ai-build-remote | — |
| 2 | AI Agent + GitHub빌드 | any | — | ai-build-remote | — |
| 3 | AI챗 + Termux 스크립트 | 스마트폰 | 스마트폰, 폰, phone | .ai/env-termux-chat.md |
| 4 | AI챗 + VS Code + Git | Win10 랩탑 | 랩탑, 노트북, laptop | .ai/env-vscode-laptop.md |
| 5 | AI챗 + Termux code-server | 갤럭시탭 S8 울트라 | 태블릿, 탭, tablet | .ai/env-termux-codeserver.md |

사용자가 "스마트폰", "폰" → 환경3 / "랩탑", "노트북" → 환경4 / "태블릿", "탭" → 환경5로 인식할 것.
브라우저만 사용 시 github.dev 또는 GitHub 웹 편집기로 커밋 가능 (별도 가이드 불필요, 긴급 시만 사용)

## AI챗 환경 분기 규칙 (환경 3/4/5)
세션 시작 시:
1. 사용자에게 작업 환경(번호 또는 기기명) 확인
2. 해당 환경 가이드 md를 읽고, 그 안의 "AI 답변 규칙"을 따를 것
3. 환경을 모르면 물어볼 것

## 토큰 전략 (환경별)

### 환경 1, 2 (Claude Code) — Pro 토큰 한도 소모
소스 읽기, 코드 출력 모두 토큰 차감. 절약 필수.

읽기 절약:
- 세션 시작 시 소스 코드 자동으로 읽지 않음
- .ai/files.md로 대상 파악 후 필요한 파일만 읽기
- 전체 파일보다 줄 범위 우선 (예: 50~80줄만)
- 한 번에 2~3개 파일, 900줄 이내
- 이미 읽은 파일 재읽기 금지

출력 절약:
- 코드만 출력, 부가 설명 최소화
- 변경된 부분만 출력 (전체 파일 재출력 지양)
- diff 또는 줄 범위 지정 교체 우선
- 확인 질문 줄이고 한 번에 작업

### 환경 3, 4, 5 (AI챗 — 젠스파크) — 토큰 무제한
소스 읽기, 코드 출력에 토큰 제한 없음. 자유롭게 읽고 출력 가능.

- 전체 파일 읽기/출력 가능
- 설명 포함 가능
- 단, 환경3(스마트폰)은 Termux 붙여넣기 크기 제한 있음 — 너무 긴 출력은 Termux 크래시 유발, 적절히 분할

## scripts/ 폴더 스크립트
- ai-copy: 파이프 결과 → 클립보드
- ai-write 파일경로: 붙여넣기 → 파일 저장 (Ctrl+D)
- ai-patch: diff 패치 적용 (Ctrl+D)
- ai-push "메시지": git add + commit + push
- ai-build-local: pull + 로컬빌드 + APK 복사
- ai-build-remote "메시지": push + GitHub빌드 + APK 다운로드
- ai-status: 현재 작업 확인
- ai-context: 세션 컨텍스트 출력 (ai-context | ai-copy → AI챗에 붙여넣기)
- setup-termux.sh: ~/bin에 심볼릭 링크 생성 (초기 설정)

## 클립보드 명령 (환경별)
| 환경 | ai-copy 내부 명령 |
|------|-------------------|
| 3 스마트폰 Termux | termux-clipboard-set |
| 4 Win10 VS Code (Git Bash) | clip.exe |
| 5 갤럭시탭 Termux | termux-clipboard-set |

## 빌드 명령 (공통)
- 빌드: ./gradlew assembleDebug
- APK 복사: cp app/build/outputs/apk/debug/app-debug.apk ~/storage/downloads/gphotosync.apk
- 에러 수: ./gradlew assembleDebug 2>&1 | grep "^e:" | wc -l
