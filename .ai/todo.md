# 할일 (2026-03-14)

## 진행 중
- 앨범 정리 실행 중 (28개 앨범, 8261개 파일)

## 다음 최적화
- getItemId 개별 호출 → listChildren 일괄 조회로 변경
  - 현재: 파일마다 getItemId API 1회 (8261개 → 8261회 호출)
  - 개선: 연도 폴더별 listChildren 1회로 파일명→ID 맵 생성
  - 효과: 앨범당 API 1~2회, 전체 수분 내 완료 예상

## 미수정 버그
- B-001: 스킵 100%일 때 UI 미갱신 (낮음)
- I-001: 로그 세션 구분/초기화 기능 (개선)

## 완료 (2026-03-14)
- OneDriveApi callback → suspend 리팩토링
- OneDriveUploader.kt 분리
- B-002: moveFile 409 스킵 처리
- B-003: createAlbum/addChild 다중 이슈 수정
- md 파일 구조 정비
- 앨범 정리 최적화: 기존 번들 children diff 후 추가분만 addChild
