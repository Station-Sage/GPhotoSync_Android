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
