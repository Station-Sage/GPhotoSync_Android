# Claude Code 프로젝트 설정

## 세션 시작 시 필수
1. `.ai/WORKFLOW.md` 읽기 (작업 규칙)
2. `.ai/CURRENT_TASK.md` 읽기 (현재 상태)
3. 필요 시: `.ai/FILE_INDEX.md`, `.ai/BUGS.md`

## Termux 환경 주의사항
- /tmp 사용 불가 → TMPDIR=$HOME/tmp 사용
- 빌드: ./gradlew assembleDebug
- APK 위치: app/build/outputs/apk/debug/app-debug.apk
- 소스 파일 300줄 제한 (초과 시 분할)

## Git 규칙
- main 브랜치에서 작업
- 커밋 후 즉시 push
- .ai/ md 파일도 함께 업데이트
