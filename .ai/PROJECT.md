# GPhotoSync - AI 개발 컨텍스트

## 개발 워크플로우
젠스파크 AI 챗 (Claude Opus) -> Termux 복사/붙여넣기 -> GitHub push -> Actions 빌드 -> APK 다운로드 -> 실행/디버그 -> 반복

## 스크립트
- ai-apply "msg": git add+commit+push -> Actions 빌드 대기 -> APK 다운로드
- ai-push "msg": git push만
- ai-write <path>: stdin -> 파일 저장

## 핵심 파일 (수정 빈도 높음)
- TakeoutUploadService.kt (1026줄): Takeout 업로드 핵심 로직
- OneDriveApi.kt (525줄): OneDrive API 클라이언트
- TakeoutTabHelper.kt (518줄): Takeout 탭 UI

## 참고
- 전체 아키텍처/API/기능 상세: README.md 참조
- 패치는 최소 범위로 (1~3 파일, diff 형식 선호)
- 긴 heredoc/cat은 Termux에서 중단됨 -> python3 -c 또는 ai-write 사용
