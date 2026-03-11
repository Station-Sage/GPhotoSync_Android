# 새 채팅 시작 템플릿

아래 내용을 새 AI 챗 첫 메시지로 붙여넣기:

---
GitHub repo: [your-repo-url]

.ai/ 폴더의 md 파일들을 먼저 읽어주세요:
- .ai/PROJECT.md (개발 워크플로, 핵심 파일)
- .ai/ARCHITECTURE.md (파이프라인, 동시성, 인증)
- .ai/FILE_INDEX.md (파일 목록, 줄수, 역할)
- .ai/CURRENT_TASK.md (현재 작업, 미해결)
- .ai/BUGS.md (버그 트래커)
- .ai/DECISIONS.md (설계 결정)
- .ai/SCRIPTS.md (개발 스크립트)

규칙:
- 코드 수정은 diff 패치 (ai-patch) 우선
- 명령 결과는 항상 | ai-copy
- 한국어로 진행
- conservative maintenance engineer 방식
---
