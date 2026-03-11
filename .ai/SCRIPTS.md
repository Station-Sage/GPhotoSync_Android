# 개발 스크립트 (~/bin/)

## ai-patch
diff 패치를 Termux에 적용. dry-run 검증 후 적용. 결과 클립보드 복사.
사용법: cat << 'PATCH' | ai-patch <파일경로> ... PATCH

## ai-copy
명령 결과를 화면 출력 + 클립보드 복사. ⚠ 덮어쓰기이므로 1개 명령씩만 사용.
사용법: 명령어 | ai-copy

## ai-apply
git push + GitHub Actions 빌드 대기 + APK 다운로드 + Download 폴더 열기.
사용법: ai-apply "커밋 메시지"

## ai-push
git push만 (빌드 대기 안 함). md 파일 업데이트 등 빌드 불필요 시 사용.
사용법: ai-push "커밋 메시지"

## ai-write
stdin으로 파일 내용 입력 후 저장.
사용법: ai-write <파일경로>

## ai-status
현재 작업 상태 확인 (.ai/CURRENT_TASK.md 출력).
사용법: ai-status

## 작업 흐름

### 세션 시작
1. README.md 읽기
2. .ai/*.md 파일 읽기
3. 소스코드는 GitHub repo에서 확인 (sed/grep 자제)

### 코드 수정
1. GitHub repo에서 소스 읽기
2. ai-patch 블록으로 수정
3. 수정 후 즉시 push (repo 최신 유지)
4. ai-copy 결과는 1개씩 (클립보드 덮어쓰기 주의)

### 빌드/설치
1. ai-apply "커밋 메시지" 실행
2. 빌드 완료 후 app-debug.apk 수동 설치

### 작업 완료
1. CURRENT_TASK.md, BUGS.md 업데이트
2. ai-push로 md 파일 push
