# 설계 결정

## D1: skipOneDriveCheck=true 기본값
이어하기 시 로컬 SharedPreferences만 확인. OneDrive HTTP 호출 생략으로 스킵 속도 대폭 개선.

## D2: Channel capacity=8, Worker 3개
메모리 사용량과 업로드 속도 균형. 8개 버퍼로 Producer가 Worker보다 앞서 읽기 가능.

## D3: OneDriveApi suspend 전환 (2026-03-14)
기존 callback 패턴 → suspendCancellableCoroutine. 11개 *Suspend 래퍼 제거, 코드 간결화.

## D4: OneDriveUploader 분리 (2026-03-14)
OneDriveApi.kt 422줄 → OneDriveApi.kt ~190줄 + OneDriveUploader.kt ~130줄. 300줄 규칙 준수.

## D5: 앨범 batchSize=20 (2026-03-14)
OneDrive 번들 생성 시 children 배열 제한. 150 → 20. 나머지는 addChild로 개별 추가.

## D6: addChild URL /bundles/ (2026-03-14)
공식 API: POST /me/drive/bundles/{id}/children. /me/drive/items/ 경로는 "item must have a name" 에러 발생.

## D7: moveFile 409 = 성공 (2026-03-14)
월→년 마이그레이션 시 대상 폴더에 동일 파일 존재 → 이미 이동된 것으로 간주, 스킵 처리.

## D8: addChild 재시도 3회 + 딜레이 (2026-03-14)
네트워크 에러(resp=0) 대응. 2초/4초/6초 딜레이. 400 "already exists"는 스킵.

## D9: 작업 환경 4, 5 추가 (2026-03-14)
기존 3개 환경에 2개 추가:
- 환경4: AI챗 + VS Code + Git (Win10 랩탑, 2코어/4GB)
  - VS Code의 Git 내장 + 터미널로 편집→커밋→푸시 원스톱
  - 클립보드: clip.exe (Git Bash)
  - 빌드: GitHub Actions만 (로컬 gradle 미설치)
- 환경5: AI챗 + Termux code-server (갤럭시탭 S8 울트라)
  - code-server로 브라우저에서 VS Code UI 사용
  - 기존 Termux 스크립트(ai-copy, ai-push 등) 100% 호환
  - Samsung DeX 분할 화면으로 AI챗 + code-server 동시 사용
- 검토 후 제외:
  - GitHub Contents API: 파일당 커밋, Base64 인코딩 번거로움
  - GitHub Git Trees API: 다단계 API, 수동 사용 비현실적
  - Claude Code GitHub Actions (@claude): API 비용 추가 발생
  - 브라우저 전용(github.dev): 별도 가이드 불필요, 긴급 시만 사용
