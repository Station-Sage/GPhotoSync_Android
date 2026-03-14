# Termux + AI 채팅 작업 가이드 (환경 3: 스마트폰)

## 환경 특성
- 기기: 스마트폰 (Android)
- AI가 직접 파일 접근 불가 — 모든 것을 복사/붙여넣기로
- 토큰 무제한 — 소스 읽기/출력 자유
- 단, 붙여넣기 크기 제한 있음 (Termux 크래시 방지)
- 스크립트 활용이 핵심

## AI 답변 규칙
- 코드 수정: 실행 가능한 bash 스크립트(heredoc)로 제공
- 여러 파일 수정: 하나의 bash 스크립트로 합쳐서 한 번만 붙여넣기
- 부분 수정: sed 명령 또는 bash 스크립트
- 전체 교체: ai-write용 heredoc (500줄 이하 파일)
- 결과 확인 명령 안내 시 항상 | ai-copy 포함
- 토큰 무제한이므로 설명 포함 가능, 단 출력 길이는 Termux 안정성 고려하여 적절히 분할

## AI 필수 규칙
1. 500줄 이하 파일은 ai-write로 전체 덮어쓰기
2. 500줄 초과 파일은 sed 또는 스크립트로 부분 수정
3. 여러 파일 수정 시 하나의 bash 스크립트로 묶어서 한 번만 붙여넣기
4. 코드 확인 명령에 항상 | ai-copy 붙이기
5. 빌드 에러 수: ./gradlew assembleDebug 2>&1 | grep "^e:" | wc -l | ai-copy
6. sed 부분 수정 시 반드시 전후 코드 확인 후 적용

## 스크립트 목록
- ai-copy: 파이프 결과를 클립보드에 복사 (예: cat file.kt | ai-copy)
- ai-write 파일경로: 클립보드 내용을 파일로 저장 (붙여넣기 후 Ctrl+D)
- ai-patch: diff 패치 적용 (붙여넣기 후 Ctrl+D)
- ai-push "메시지": git add + commit + push
- ai-build-local: 로컬빌드 + APK 복사
- ai-build-remote "메시지": push + GitHub빌드 + APK 다운로드
- ai-status: 현재 작업 확인

## 작업 사이클
1. 코드 확인: sed -n '10,30p' 파일.kt | cat -n | ai-copy
2. 수정 (500줄 이하): ai-write 파일경로 → 전체 코드 붙여넣기 → Ctrl+D
3. 수정 (500줄 초과): AI가 준 bash 스크립트 한 번에 붙여넣기
4. 빌드 확인: cd ~/GphotoSync && ./gradlew assembleDebug 2>&1 | grep "^e:" | wc -l | ai-copy
5. 에러 시: ./gradlew assembleDebug 2>&1 | grep "^e:" | ai-copy → AI에게 전달
6. 성공 시: cp app/build/outputs/apk/debug/app-debug.apk ~/storage/downloads/gphotosync.apk

## 빌드 에러 분석
- 에러 수: ./gradlew assembleDebug 2>&1 | grep "^e:" | wc -l | ai-copy
- 에러 상세: ./gradlew assembleDebug 2>&1 | grep "^e:" | ai-copy
- 파일별 에러: ./gradlew assembleDebug 2>&1 | grep "^e:" | sed 's/.*gphotosync\///' | cut -d: -f1 | sort | uniq -c | sort -rn | ai-copy
