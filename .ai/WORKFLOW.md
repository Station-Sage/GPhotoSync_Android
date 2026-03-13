# AI 워크플로 (Genspark / Claude Code)

## 프로젝트 요약
GPhotoSync — Google Takeout ZIP → Microsoft OneDrive 업로드 Android 앱.
두 경로: (A) Google Photos Picker 실시간 동기화, (B) Takeout ZIP 스트리밍 대량 업로드.
GitHub: https://github.com/Station-Sage/GPhotoSync_Android

## 세션 시작 절차
1. WORKFLOW.md (이 파일) 읽기
2. CURRENT_TASK.md 읽기 — 현재 할 일 + 읽어야 할 소스 파일 목록 확인
3. 필요한 소스 파일만 읽기 (전체 repo 읽지 말 것)

## 파일 읽기 레벨
- **Level 0 (항상)**: WORKFLOW.md, CURRENT_TASK.md
- **Level 1 (필요 시)**: FILE_INDEX.md (파일/함수 위치), BUGS.md (버그 작업 시)
- **Level 2 (해당 시만)**: ARCHITECTURE.md (구조 변경), DECISIONS.md (설계 변경)
- **읽지 않음**: README.md (사람용)

## 토큰 절약 규칙
- 전체 repo, 전체 소스를 한번에 읽지 말 것
- 소스코드는 파일 단위 또는 함수 단위로 읽을 것
- FILE_INDEX.md의 함수 인덱스로 필요한 행 범위 특정 후 읽기
- 소스 1파일 300줄 이하 유지 (초과 시 분할)
- md 파일도 줄수 제한 준수

## md 파일 관리
| 파일 | 최대 줄수 | 수정 방식 | 시점 |
|------|-----------|-----------|------|
| CURRENT_TASK.md | 30줄 | 전체 덮어쓰기 | 매 작업 시작/완료 |
| BUGS.md | 40줄 | 전체 덮어쓰기 | 버그 추가/완료 (완료 항목 삭제) |
| FILE_INDEX.md | 60줄 | 전체 덮어쓰기 | 소스 수정/분할 후 |
| DECISIONS.md | 50줄 | 하단 추가 또는 전체 | 설계 결정 시 (폐기 결정 삭제) |
| ARCHITECTURE.md | 40줄 | 전체 덮어쓰기 | 구조 변경 시 (드묾) |

## 코드 수정 규칙
- 직접 파일 읽기/쓰기 (sed/grep 불필요)
- 수정 후 즉시 git commit + push
- md 파일도 작업 완료 시 함께 수정/push
- 빌드: ./gradlew assembleDebug

## 소스 파일 규칙
- 1파일 300줄 이하 (초과 시 분할 필수)
- GitHub raw URL: https://raw.githubusercontent.com/Station-Sage/GPhotoSync_Android/main/{경로}
