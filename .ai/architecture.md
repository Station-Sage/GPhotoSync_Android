# 아키텍처

## 앱 구조
Android Service 기반. 4개 탭: Sync(실시간), Auth(인증), Info(정보), Takeout(대량 업로드).

## 두 가지 업로드 경로
- (A) Sync: Google Photos Picker → OneDrive 실시간 동기화
- (B) Takeout: Google Takeout ZIP → OneDrive 대량 업로드

## Takeout 업로드 흐름
1. ZIP 선택 → 분석 (JSON 메타데이터 파싱, 미디어 목록 추출)
2. Producer 코루틴: ZipArchiveInputStream으로 스트리밍 읽기
3. Channel(capacity=8): 버퍼링
4. Worker 3개: 병렬 업로드 (small < 4MiB, large ≥ 50MiB chunked)
5. 완료 후: 앨범 정리, 월→년 마이그레이션

## OneDrive API 구조
- OneDriveApi.kt: suspend 함수 (suspendCancellableCoroutine + OkHttp async)
- 폴더: POST /me/drive/root/children (409=이미존재→성공)
- 업로드: PUT (small), POST createUploadSession + PUT chunks (large)
- 앨범: POST /me/drive/bundles (생성), POST /me/drive/bundles/{id}/children (추가)
- 이동: PATCH /me/drive/items/{id} parentReference (409=이미존재→스킵)
- 삭제: DELETE /me/drive/items/{id}

## 토큰 관리
- TokenManager: EncryptedSharedPreferences (AES-256-GCM)
- Google: OAuth2 refresh via googleapis.com
- Microsoft: OAuth2 refresh via login.microsoftonline.com
- 동시 refresh 방지: volatile flag + synchronized lock

## 상태 저장
- SharedPreferences: 업로드 진행률, 스킵 목록, drive item ID, 앨범 맵
- 이어하기: 로컬 SharedPreferences만 확인 (skipOneDriveCheck=true)
