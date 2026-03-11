# 개발 스크립트 (~/bin/)

## ai-patch
diff 패치를 Termux에 적용. dry-run 검증 후 적용. 결과 클립보드 복사.
사용법: ai-patch << 'PATCH' ... PATCH

## ai-copy
명령 결과를 화면 출력 + 클립보드 복사.
사용법: 명령어 | ai-copy

## ai-apply
git push + GitHub Actions 빌드 대기 + APK 다운로드 + Download 폴더 열기. 결과 클립보드 복사.
사용법: ai-apply "커밋 메시지"

## ai-push
git push만 (빌드 대기 안 함).
사용법: ai-push "커밋 메시지"

## ai-write
stdin으로 파일 내용 입력 후 저장.
사용법: ai-write <파일경로>

## ai-status
현재 작업 상태 확인 (.ai/CURRENT_TASK.md 출력).
사용법: ai-status

## 작업 흐름
### 코드 수정 (AI -> Termux)
1. AI가 ai-patch 블록 출력
2. 사용자가 블록 복사 -> Termux 붙여넣기
3. 패치 자동 적용

### 결과 확인 (Termux -> AI)
1. 명령어 | ai-copy 실행
2. AI 챗에서 붙여넣기

### 빌드/설치
1. ai-apply "커밋 메시지" 실행
2. 빌드 완료 후 Download 폴더 자동 열림
3. app-debug.apk 탭하여 수동 설치 (3탭)

### 새 채팅 시작
1. .ai/PROMPT_TEMPLATE.md 내용 복사
2. AI 챗 첫 메시지로 전송

### 작업 완료 시
1. AI에게 CURRENT_TASK.md, BUGS.md 업데이트 요청
