# DualTouch DT-004~009 안전·기능 개발 QA
기준 2026-11-19. 사용자의 지시대로 실제 폰·폴드·태블릿 QA는 전체 개발 후 진행합니다.

## 구현 범위
DT-004: Android 프로젝트/AGENTS/README/CI, lintDebug와 제한 권한 감사.
DT-005: 정확히 두 대상 앱, 동일 디스플레이/포커스/분할, 키보드·타 앱·시스템 팝업·타 접근성 오버레이 차단. 좌표/영점·방향 제한.
DT-006: 원본+자동 터치 재현 전 창 검사, 콜백 세션 토큰 검증, OFF·메뉴·중지·시간 제한 때 대기 중 자동 두 번째 터치 차단.
DT-007: 최초 접근성 사용 고지·명시 동의, 시스템 설정 사용자 수동 허용, 동의 철회, 화면 꺼짐·회전·Fold·서비스 해제에 따른 fail-closed.
DT-008: 기존 반응형 홈/상태·3버튼 Thumb·영점·가이드·권한 UI의 입력 상태 연동과 오류·복구 안내.
DT-009: 화면 표시·좌우 방향/영점 로컬 저장, 서비스 재연결/재시작 시 기본 OFF, 입력 상태·실패 사유 제공.

## 자동 CI
- [ ] WindowSafetyPolicy 키보드/오버레이/추가 앱/다른 디스플레이/큰 시스템창/포커스 테스트
- [ ] DispatchGeneration 세션 무효화 테스트
- [ ] InteractionPhase OFF/대기/준비/실행/일시정지 테스트
- [ ] 기존 WindowPair/CoordinateMapper/Calibration/Appearance/AdaptiveLayout 회귀 테스트
- [ ] 앱 lintDebug, Kotlin unitTest, app/probe-left/probe-right debug APK 3종
- [ ] Manifest INTERNET/SYSTEM_ALERT_WINDOW/QUERY_ALL_PACKAGES/BIND_DEVICE_ADMIN 부재 검사

## 실기기 통합 QA (미실행)
- [ ] 접근성 동의 거절·철회, 서비스 시스템 비활성화 후 캡처·플로팅 중지
- [ ] 허용한 두 테스트 앱 외에 타 앱·보안/결제/키보드 화면에 오입력 0회
- [ ] Fold 좌우 5:5 직접/자동 입력 각 30회·중복 0회, 실제 원본 입력 복구
- [ ] 실행 중 메뉴 및 영점 진입, 입력 중지 후 대기 중 두 번째 클릭 0회
- [ ] 화면 OFF/회전·폴드 접힘·다른 앱·분할 크기 변경 및 20초 제한·볼륨 아래 중단
- [ ] 실제 폰·태블릿·Fold UI 가독성/200% 글자/라이트·다크/TalkBack/오버레이 포커스
- [ ] 프로세스 재시작에 따른 ACTIVE 자동 복원 0회, 설정값만 정상 보존

주의: Android onMotionEvent 수신 시 입력이 앱으로 전달되지 않고 dispatchGesture는 진행 중 터치를 취소합니다. CI가 성공해도 기능 GO/NO-GO는 실기기 QA 전 미확정입니다.
