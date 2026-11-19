# DualTouch — DT-002 Android 기술검증

Galaxy Z Fold 펼침 화면의 두 **독립적인 테스트 앱 창**에서 사용자의 한 번의 터치를 양쪽 창에 재현할 수 있는지 확인하는 실험 프로젝트입니다.

> **기술 POC 전용입니다.** Android 접근성 서비스는 실제 터치 입력을 가로채고 진행 중인 입력을 취소할 수 있습니다. 일반 앱/결제/금융/계정 화면을 제어하지 않으며, Fold 실기기 검증 전에는 실제 동작을 보증하지 않습니다.

## 구성

- `:app` — 실험 제어 앱, 접근성 서비스, 좌표·창 탐지, 세 가지 실험 모드
- `:probe-common` — 두 개의 독립 앱에 포함되는 탭 카운터 화면
- `:probe-left` — 좌측 테스트용 독립 패키지 `com.hebi.dualtouch.probeleft`
- `:probe-right` — 우측 테스트용 독립 패키지 `com.hebi.dualtouch.proberight`

Android API 34 이상에서 테스트합니다. 프로젝트는 Kotlin/Android SDK만 사용하며 로그인·서버·인터넷 권한은 없습니다. Gradle 8.11.1 + JDK 17 + Android SDK 35가 필요합니다.

## 디버그 빌드

```bash
gradle :app:assembleDebug :probe-left:assembleDebug :probe-right:assembleDebug :app:testDebugUnitTest
```

생성되는 APK:
- `app/build/outputs/apk/debug/app-debug.apk`
- `probe-left/build/outputs/apk/debug/probe-left-debug.apk`
- `probe-right/build/outputs/apk/debug/probe-right-debug.apk`

GitHub Actions의 `Android PoC CI` 실행 아티팩트에도 세 디버그 APK가 생성됩니다(워크플로 성공 시).

## Fold 실기기 절차

1. 세 APK를 기기에 설치합니다. DualTouch를 실행하여 사용 목적과 위험을 읽고, Android 설정에서 DualTouch 접근성 서비스를 수동으로 켭니다.
2. 시스템 멀티윈도우 기능으로 **Test Left** 앱과 **Test Right** 앱을 좌우 5:5로 실행합니다. 두 앱은 서로 다른 패키지이므로 동일 앱 멀티인스턴스 허용 여부와 무관하게 독립 창으로 실험할 수 있습니다.
3. DualTouch로 전환하여 실험 모드(동시 두 스트로크/순차 두 탭/접근성 노드 클릭)를 고르고 **20초간 대기 시작**을 선택한 뒤, 분할 화면으로 돌아갑니다.
4. 준비된 두 창이 감지되면 터치 수집이 시작됩니다. **왼쪽 테스트 버튼**을 직접 눌러 각 창 카운터가 각각 1회 증가하는지 확인합니다. 중앙 10회, 네 곳 각 5회(총 30회)로 시험하고 화면을 녹화합니다.
5. **긴급 정지: 기기 볼륨 아래 버튼**을 누릅니다. 자동 20초 제한, 테스트 창 이탈, 손가락 드래그/다중 터치에도 중지되도록 설계했습니다.
6. 각각의 모드를 별도 시도로 검사합니다. `dispatchGesture` 완료 콜백은 UI의 실제 클릭 성공을 증명하지 않으므로 양쪽 테스트 앱 카운터를 기준으로 판정합니다.

**주의:** 실험 중에는 터치가 통째로 수집되므로 스크롤·내비게이션 제스처가 작동하지 않거나 일부 터치가 소실될 수 있습니다. 먼저 볼륨 아래 비상 중지를 확인하세요. 실험은 개인 테스트 앱에서만 실시하고 실제 계정 앱에는 사용하지 마세요.

## 실험 모드

- **동시 스트로크**: 원본 터치를 수집한 뒤 두 좌표를 포함한 하나의 다중 포인터 `GestureDescription`을 전송합니다. 서로 다른 창에 각각 전달되는지는 Fold에서 검증해야 합니다.
- **순차 탭**: 첫 번째 스트로크 완료 콜백 후 두 번째를 전송합니다. 완전히 동시에 실행되는 방식은 아닙니다.
- **접근성 노드 클릭**: 좌·우 테스트 앱의 화면 노드에서 클릭 가능한 항목을 찾아 `ACTION_CLICK`을 실행합니다. 일반 게임·캔버스의 범용 터치 대안이 아닙니다.

실험 중 물리 입력을 캡처하기 위해 Android API 34+ `onMotionEvent`와 `setMotionEventSources`를 사용합니다. 해당 입력은 원래 앱으로 전달되지 않으므로 원본 탭을 **재생**해야 합니다. 합성 입력이 원래 제스처를 취소하거나 이벤트를 재귀 수신하는지 관찰합니다.

## 기술 GO / NO-GO

- 30회 중 좌우 각각 정확히 1회 증가하는 경우 29회 이상
- 이중 클릭·오조작·긴급 중지 실패·제스처 무한 재귀: 0회
- 원본 터치 소실·성능·TouchExploration과의 충돌은 별도 기록
- 위 기준은 최초 시험용 제안 기준이며 실기기에서 검증하지 않았습니다.

## 관련 자료

- [제품요구사항](https://docs.google.com/document/d/1iTtV9sP_YaZkVkYxD2Wf8OY6D1BiWLlzG-slIqnyf3Q/edit)
- [DT-002 실험 지시서](https://docs.google.com/document/d/1U1yY_BZFt_6kfJpZ53lxkyUR1nDEtiEYoeO_cSDOapc/edit)
- [QA 계획](https://docs.google.com/document/d/1nKgSbTeevrEScDw8T_yz-af_mJKQ2GdTMjgJf330cWY/edit)

## 배포 정책

PR/main에서는 디버그 빌드와 단위 테스트만 자동 실행합니다. 서명 AAB·Google Play 제출·테스트 트랙 승격·정식 출시는 사용자 별도 승인 전에 실행하지 않습니다.
