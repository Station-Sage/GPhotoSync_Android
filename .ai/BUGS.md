# 버그 및 개선사항 (2026-03-13)

## P1 (즉시 수정) — 모두 완료
- ✅ UI 복원 (activity recreation)
- ✅ 업로드 속도 저하 (skipOneDriveCheck=true)
- ✅ 대용량 영상 크래시 (청크 재시도, OOM catch)

## P2 (중요)
- ✅ P2-1: 인증 탭 토큰 만료 표시 오류
- ✅ P2-2: MS 재인증 후 업로드 자동 재시작 안 됨
- ✅ P2-3: 업로드 중 토큰 만료 처리 없음
- ✅ P2-4: 알림/앱 내 진행률 불일치
- ✅ P2-5: 중단 버튼 1회 클릭 즉시 중단 안 됨 + 2회 클릭 시 앱 종료 (stopSelf() 추가, 실기기 테스트 필요)
- ✅ P2-6: 토큰 만료 시 즉시 중단 + 재인증 팝업 (5회 연속 실패 감지)
- ⏳ P2-7: 대용량 파일 OOM → 스트리밍 청크 업로드로 해결 (862MB 성공, 전체 완료 테스트 필요)
- ❌ P2-8: Azure 공개 클라이언트 설정 (invalid_client — 토큰 refresh 불가)

## P3 (개선)
- ✅ P3-8: sync_log.txt MediaStore API로 Downloads 쓰기
- ✅ P3-13: 분석 UI 97% 멈춤 → 100% 정상 완료 확인
- ✅ P3-9: sync_log.txt 쓰기 권한 (MediaStore로 해결, Termux 접근 확인됨)
- ✅ P3-10: listChildren @odata.nextLink 페이징 구현 완료 (OneDriveApi.kt 366-387줄)
- ✅ P3-11: 취소 후 진행바 리셋 추가 (TakeoutTabHelper.kt)
- ❌ P3-12: 대용량 번들 앨범 성능 저하

## 코드 품질
- ✅ TakeoutUploadService.kt 4파일 분할
- ✅ OneDriveApi.kt 전체 재작성 (643→422줄)
- ✅ 미사용 코드 제거 (updateWorkerLog, .orig 등)
