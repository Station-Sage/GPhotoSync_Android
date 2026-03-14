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
| .ai/env-termux-chat.md | Termux + AI챗 환경 상세 가이드 |

## 작업 환경
| 환경 | 빌드 | 스크립트 |
|------|------|---------|
| 1. Claude Code (웹/앱) + GitHub빌드 | ai-build-remote "메시지" | push → Actions → APK 다운 |
| 2. Claude Code CLI + Termux 로컬빌드 | ai-build-local | pull → 빌드 → APK 복사 |
| 3. AI챗 + Termux 복사/붙여넣기 | ai-build-local | .ai/env-termux-chat.md 참조 |

## scripts/ 폴더 스크립트
- ai-copy: 파이프 결과 → 클립보드
- ai-write 파일경로: 붙여넣기 → 파일 저장 (Ctrl+D)
- ai-patch: diff 패치 적용 (Ctrl+D)
- ai-push "메시지": git add + commit + push
- ai-build-local: pull + 로컬빌드 + APK 복사
- ai-build-remote "메시지": push + GitHub빌드 + APK 다운로드
- ai-status: 현재 작업 확인
- setup-termux.sh: ~/bin에 심볼릭 링크 생성 (초기 설정)

## 빌드 명령 (공통)
- 빌드: ./gradlew assembleDebug
- APK 복사: cp app/build/outputs/apk/debug/app-debug.apk ~/storage/downloads/gphotosync.apk
- 에러 수: ./gradlew assembleDebug 2>&1 | grep "^e:" | wc -l
