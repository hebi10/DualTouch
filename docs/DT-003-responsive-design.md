# DualTouch DT-003-R01 — 스마트폰·폴드·태블릿 반응형 UI 설계

기준: 2026-11-19 / GitHub PR #1 / 기능 범위: 기존 POC 화면·상태·설정·오버레이 배치 개선. 실기기 사용성 최종 검증은 전체 개발 종료 후.

## 사용자 경험 원칙

1. 폴드의 기종이나 태블릿 여부가 아니라 **현재 앱 창의 실제 공간**을 기준으로 레이아웃을 구성한다. 두 앱 분할 상태에서는 화면 크기의 절반인 앱 창도 정상 사용 가능해야 한다.
2. 홈의 주 기능 '플로팅 표시/숨기기'를 설정·설명보다 먼저 제공한다. 휴대폰에서는 스크롤 없는 상단 제어, 태블릿은 왼쪽 제어 패널과 오른쪽 도움말.
3. 환경이 달라도 기능이 누락되지 않는다. 1열/2열을 바꿀 때 설정값·현재 페이지·스크롤을 유지하고 가로회전에도 텍스트가 잘리지 않도록 한다.
4. Thumb 3버튼 메뉴는 화면 가장자리 안에서 열리고 높이가 부족하면 내부 스크롤로 전체 버튼 접근이 가능해야 한다.
5. 48dp 이상 터치 영역, 최소 가시 불투명도 40%, 큰 글자에서도 라벨이 읽히도록 한다.

## 크기별 UI

- Compact: <600dp. 홈 단일 컬럼, 12dp 외부 여백, 12dp 내부 패딩. 설정 단일 컬럼, 색상 팔레트 가로 스크롤.
- Medium: 600~839dp. 읽기 편한 최대 720dp 중앙 1열. 설정 슬라이더/팔레트가 가로폭 전체를 이용.
- Expanded: ≥840dp와 높이 ≥480dp. 홈 왼쪽 상태·플로팅 제어, 오른쪽 준비 단계·문제 해결. 설정 왼쪽 미리보기, 오른쪽 Thumb·영점 기준점 설정. 전체 폭 상한 1180dp.
- Short landscape: 높이 <480dp. 가로폭이 넓어도 한 열을 사용, 세로 스크롤로 모든 버튼 접근.

## 시각 언어

배경 라이트 백색·중성회색·검정, 다크 차콜·회색·흰색. 불필요한 그림자·그래디언트·장식 애니메이션 없음. 모든 단계 동일한 시스템 글꼴과 명료한 텍스트 계층 유지.

Thumb 메뉴는 화면의 상태바·내비게이션 안전 영역을 고려해 위치를 제한하고, 큰 글자로 메뉴 높이가 커질 때도 화면 밖으로 잘리지 않는 스크롤 콘텐츠를 사용한다. 빨강은 직접 터치·흰색은 자동 터치라는 기본 식별을 유지하되 설정 팔레트 변경도 지원한다.

## 기능·코드 매핑

- app/src/main/java/com/hebi/dualtouch/AdaptiveLayout.kt — 창 크기/높이 등급·콘텐츠 최대 너비
- app/src/test/java/com/hebi/dualtouch/AdaptiveLayoutTest.kt — 여섯 가지 화면/경계 단위 테스트
- app/src/main/java/com/hebi/dualtouch/UiKit.kt — 중앙 정렬·2열 패널·시스템 바 Insets
- app/src/main/java/com/hebi/dualtouch/MainActivity.kt — 홈 2열과 나머지 화면 최대 읽기 폭, 페이지·스크롤 복원
- app/src/main/java/com/hebi/dualtouch/SettingsActivity.kt — 미리보기/슬라이더 2열, 작은 화면 가로 스크롤 팔레트·48dp 색상 선택 영역
- app/src/main/java/com/hebi/dualtouch/ThumbOverlayController.kt — 시스템 영역 안전 여백·플로팅 컨트롤 위치·큰 글자용 메뉴 스크롤

## 검증 단계

- 자동: Kotlin/Android Debug 3 APK, AdaptiveLayout 경계 테스트, 기존 설정값/좌표/영점 테스트.
- 미실행: 320/360/412/600/700/840/1200dp 실제 화면·라이트/다크·200% 글자·태블릿 화면 회전·Fold 접힘/펼침·TalkBack·플로팅 제스처/버튼 중복 클릭.
- POC 본질 제약: 화면 설계를 개선해도 Android의 가상 터치 재생 성공과 안전성을 보장하지는 않는다. 반드시 전체 개발 이후 최종 Fold 실기기 QA에서 별도 확인한다.

Android 가이드: https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes
