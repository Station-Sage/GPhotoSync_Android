# GPhotoSync - AI 개발 컨텍스트

## 개발 워크플로우
젠스파크 AI 챗 (Claude Opus) -> Termux 복사/붙여넣기 -> GitHub push -> Actions 빌드 -> APK 다운로드 -> 설치 -> 디버그 -> 반복

## 스크립트 (~/bin/) - 상세: .ai/SCRIPTS.md
- ai-patch: diff 패치 적용 (AI 출력 블록 그대로 붙여넣기)
- ai-copy: 실행 결과 클립보드 복사 (AI 챗에 붙여넣기용)
- ai-apply: git push + 빌드 + APK 다운로드 + 폴더 열기
- ai-push: git push만
- ai-write: stdin -> 파일 저장 (새 파일용)
- ai-status: 현재 작업 상태 확인

## 핵심 파일 (수정 빈도 높음)
- TakeoutUploadService.kt (1026줄): Takeout 업로드 핵심 로직
- OneDriveApi.kt (525줄): OneDrive API 클라이언트
- TakeoutTabHelper.kt (518줄): Takeout 탭 UI

## AI 작업 규칙
- 코드 수정은 ai-patch용 diff로 출력
- 결과 확인 명령은 | ai-copy 포함
- 새 파일은 python3 스크립트 또는 ai-write 사용
- 긴 heredoc 금지 (Termux에서 중단됨)
- 전체 파일 재작성 금지, 최소 패치만
- 전체 아키텍처/API/기능 상세: README.md 참조
