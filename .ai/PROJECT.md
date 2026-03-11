# GPhotoSync Android

## 개발 워크플로
Claude Opus (젠스파크) -> Termux 복사/붙여넣기 -> GitHub push -> Actions 빌드 -> APK 다운로드 -> 수동 설치 -> 디버그 -> 반복

## 스크립트 (~/bin/)
.ai/SCRIPTS.md 참고: ai-patch, ai-copy, ai-apply, ai-push, ai-write, ai-status

## 핵심 파일 (수정 빈도 높음)
- TakeoutUploadService.kt (~1030줄): 업로드 핵심 로직
- TakeoutTabHelper.kt (~550줄): UI 상태관리, 공통함수
- OneDriveApi.kt (~525줄): MS Graph API

## AI 작업 규칙
1. 코드 수정은 ai-patch (diff) 우선, 안 되면 sed
2. 명령 결과는 항상 | ai-copy
3. 새 파일은 python3 또는 ai-write
4. Termux 긴 heredoc 주의 (이스케이프 문제)
5. 전체 파일 재작성 금지 - 최소 패치만
6. README.md에 전체 아키텍처/API 설명 있음
