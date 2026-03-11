# 현재 작업 (2026-03-11)

## 오늘 완료
1. authExpiredCallback 구문 오류 수정
2. MS 인증 만료 시 AlertDialog + 인증 탭 이동 안내
3. MS 토큰 검증에 api.client 재사용
4. 라디오 버튼 UX 수정 (JSON 선택 시 rbManual 유지)
5. btnStartTakeout visibility 누락 수정
6. restoreState() 3상태 분기 리팩토링 (분석중/업로드중/대기중)
7. applyUploadingUI / applyFinishedUI / applyIdleUI 공통 함수 추출
8. 로그 버퍼 복원 중복 제거
9. 탭 위치 SharedPreferences 저장/복원
10. currentProgress 업로드 시작 시 초기화
11. ensureFolder 실패 시 logcat 출력 추가
12. folderMutex → 폴더별 락(ConcurrentHashMap)으로 병렬성 향상
13. 속도 표시 수정 (actualUploadStartTime 기준)
14. ZIP 파일 비교를 URI 대신 파일명으로 변경 (분석 결과 보존)
15. README.md + .ai 문서 업데이트

## 검증 필요 (실기기 테스트)
1. 같은 ZIP 재선택 시 분석 결과 재사용되는지
2. 업로드 속도 표시 정상 여부
3. 폴더 생성 실패 빈도 감소 여부
4. 앱 전환 후 복귀 시 UI 일관성 (프로그레스바/버튼/로그)
5. 업로드 중단 후 업로드 버튼 + 이어하기 버튼 모두 표시
6. 업로드 완료 후 앨범/마이그레이션/재분석 버튼 표시
7. 알림창 진행 상태와 앱 내 프로그레스 일치 여부

## 다음 우선순위
- 대용량 동영상 업로드 중 앱 멈춤/끊김 수정
- 스킵 최적화 (로컬 SharedPreferences만으로 스킵 판단)
- 인증 상태 표시 개선 (토큰 만료 여부 반영)
