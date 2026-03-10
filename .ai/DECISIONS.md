# 설계 결정

## D1: uploaded + checkFileExistsSuspend 통합
새 업로드/이어하기 동일 로직. uploaded에 있으면 OneDrive 확인 -> 있으면 스킵, 없으면 재업로드.

## D2: yearOnly 연도만 반환
jsonDateMap 이전 데이터 "연도/월" -> substringBefore("/")로 연도만 추출.

## D3: 폴더 생성 전략
서비스 시작 시 루트 폴더 미리 생성 + listChildren으로 연도 폴더 캐싱.
Worker에서 folderMutex로 동시 생성 방지. ensureFolderSuspend 3회 재시도.

## D4: conflictBehavior: "fail"
createFolderChain에서 사용. 409도 성공 처리 (폴더 존재).

## D5: START_REDELIVER_INTENT
시스템 킬 후 마지막 Intent로 서비스 재시작. URI 없는 호출은 START_NOT_STICKY.

## D6: Termux 개발 제약
- 긴 heredoc 중단됨 -> python3 스크립트 사용
- 로컬 빌드 불가 -> GitHub Actions 의존
- sync_log.txt 경로: /storage/emulated/0/Download/ (Termux ~/storage/downloads와 다름)
