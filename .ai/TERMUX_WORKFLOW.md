# Termux 워크플로

## 전제
Termux에서 Claude Opus → 복사/붙여넣기 → GitHub push → Actions 빌드 → APK 설치.
이 파일은 Termux 사용 시에만 읽을 것.

## 스크립트 (~/bin/)
| 명령 | 기능 |
|------|------|
| ai-patch | diff 패치 적용 (dry-run 검증 후 적용) |
| ai-copy | 명령 결과 출력 + 클립보드 복사 (1개씩만) |
| ai-apply | git push + Actions 빌드 대기 + APK 다운로드 |
| ai-push | git push만 (빌드 불필요 시) |
| ai-write | stdin → 파일 저장 |
| ai-status | CURRENT_TASK.md 출력 |

## 코드 수정 우선순위
1. ai-patch (diff 패치) — 가장 정확
2. sed — 단순 치환 시
3. ai-write (전체 파일) — 최후 수단

## 주의사항
- ai-copy는 클립보드 덮어쓰기 → 1개 명령씩만
- sed/grep은 최소한으로 사용
- GitHub raw URL로 소스 확인 후 패치 생성
- 프로젝트 경로: ~/GphotoSync
