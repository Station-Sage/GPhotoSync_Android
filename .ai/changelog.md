# 변경 이력

## 2026-03-14

### 리팩토링: OneDrive API suspend 전환
- OneDriveApi.kt: 전체 메서드 callback → suspend (suspendCancellableCoroutine + OkHttp async)
- OneDriveUploader.kt: 신규 생성 — simple upload (≤4MiB), chunked upload (>4MiB, 10MiB chunks)
- ChunkSource 추상화: Memory/FileSource로 메모리/파일 소스 통합
- OneDriveFiles.kt: 삭제 — 기능을 OneDriveApi.kt로 통합
- TakeoutUploadService.kt: 11개 *Suspend 래퍼 함수 제거, OneDriveApi 직접 호출
- SyncForegroundService.kt: suspendCancellableCoroutine 래퍼 제거, suspend API 직접 호출
- TakeoutUploadPipeline.kt: listChildrenSuspend 등 직접 호출로 교체

### B-002 수정: 월→년 마이그레이션 moveFile 409
- 원인: 대상 폴더에 동일 파일이 이미 존재
- 수정: OneDriveApi.moveFile에서 409를 성공으로 처리 + 로그 출력
- 파일: OneDriveApi.kt 126번 줄

### B-003 수정: 앨범 정리 createAlbum/addChild 다중 이슈
- 이슈1: batchSize 150 → OneDrive 제한 초과 → 20으로 축소
- 이슈2: addChild URL /me/drive/items/{bid}/children → /me/drive/bundles/{bid}/children
  - 원인: MS 공식 API는 bundles 경로. items 경로는 "item must have a name" 에러
- 이슈3: 이미 앨범에 있는 아이템 재추가 시 400 "already exists" → 스킵 처리
- 이슈4: 대형 앨범(수백 개) 연속 호출 시 네트워크 에러(resp=0) → 재시도 3회 + 딜레이(2s/4s/6s)
- 결과: 28개 앨범 중 25개 성공 (818개 파일 앨범 포함), 3개는 네트워크 에러로 재실행 필요

### 문서 구조 정비
- AGENTS.md: 신규 생성 (범용 AI 도구 지원)
- CLAUDE.md: .ai/index.md 참조 방식으로 개편
- .ai/ 폴더 재구성:
  - 신규: index.md (라우터), todo.md, env-termux-chat.md, changelog.md, files.md
  - 삭제: BUGS.md (루트와 중복), CURRENT_TASK.md, WORKFLOW.md, TERMUX_WORKFLOW.md, FILE_INDEX.md
  - 업데이트: architecture.md, decisions.md
- BUGS.md: 루트 단일 소스로 통합

### 테스트 결과
- Takeout 업로드: 8261개 파일, 1536개 신규 업로드, 0개 실패, 평균 1.7~2.7MB/s
- 마이그레이션: 모든 월 폴더 → 연도 폴더 이동 완료 (409 스킵)
- 앨범 정리: 25/28 성공 (최대 818개 파일 앨범 처리 확인)
