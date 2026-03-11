# GPhotoSync Android

## AI 워크플로 (최우선 규칙)
1. **세션 시작**: README.md → .ai/*.md 순서로 읽는다
2. **소스코드 확인**: GitHub repo raw URL에서 직접 읽는다
3. **sed/grep 최소화**: 소스 확인용 sed/grep은 최대한 자제한다
4. **파일 단위 수정 후 즉시 push**: repo에 항상 최신 버전을 유지한다
5. **파일 크기 제한**: 소스코드 1파일 1000줄 미만. 초과 시 파일 분리한다
6. **md 파일 최신 유지**: 작업 완료 시 관련 md 파일도 함께 수정하고 push한다

## 개발 환경
Claude Opus (젠스파크 AI Chat) → Termux 복사/붙여넣기 → GitHub push → Actions 빌드 → APK 다운로드 → 수동 설치 → 디버그 → 반복

## 스크립트 (~/bin/)
.ai/SCRIPTS.md 참고: ai-patch, ai-copy, ai-apply, ai-push, ai-write, ai-status

## 핵심 파일 (수정 빈도 높음)
- TakeoutUploadService.kt (~1030줄): 업로드 핵심 로직 ⚠ 1000줄 초과 — 분리 필요
- TakeoutTabHelper.kt (~560줄): UI 상태관리, 공통함수
- OneDriveApi.kt (~530줄): MS Graph API

## 코드 수정 방식 (우선순위)
1. ai-patch (diff 패치) — 가장 정확
2. sed 부분 교체 — patch 실패 시
3. 전체 파일 재작성 — 최후 수단, 가급적 금지
