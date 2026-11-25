# DT-RELEASE-01 — 0.0.4-poc 내부 QA 후보

2026-11-24 KST. Draft PR #3에서 다음 버전을 준비합니다.

- applicationId: com.hebi.dualtouch
- versionName: 0.0.4-poc
- versionCode: 4 (마지막 확인된 내부 게시 기록은 0.0.3-poc / code 3. 업로드 전에 Play Console의 최신 사용 코드 재확인.)
- minSdk 34 / targetSdk 36 유지.
- DT-019, DT-FIX-01~03, DT-UX-01~04 포함. 기술 QA 후보이며 실기기 동기화가 검증됐다고 표기하지 않습니다.
- 스토어 설명 초안의 좌우·상하 분할, Thumb 상태, 세션 영점 보관 설명 및 출시 노트를 현재 코드에 맞게 정리합니다. Play Console 실제 등록정보는 변경하지 않았습니다.

## 선행 증빙

- DT-FIX-01~03 CI 성공: https://github.com/hebi10/DualTouch/actions/runs/45321736811
- DT-UX-01~04 첫 통합 CI 성공: https://github.com/hebi10/DualTouch/actions/runs/45322414544
- 7d86823 최종 안전 보완: https://github.com/hebi10/DualTouch/actions/runs/45322489656 — 버전 상향 직전 조회에서 Kotlin·Lint·APK·권한 감사 성공, 에뮬레이터 UI 진행 중.
- 실기기 검증 계획: https://docs.google.com/document/d/1nKgSbTeevrEScDw8T_yz-af_mJKQ2GdTMjgJf330cWY/edit

## 배포 조건

1. 버전 상향 포함 최종 후보 CI 확인 및 동일 후보 실기기 입력·안전 검증. 고위험 변경의 Draft 상태 유지.
2. 검증된 PR 병합 및 고정 main CI 성공 확인.
3. Play Console에서 최신 사용 versionCode 확인. 등록된 업로드 키로 internal-qa-bundle.yml 수동 실행, AAB 패키지·버전·출처·서명 검증.
4. 기존 내부 테스터에만 게시, 활성 버전과 설치 링크 확인. 비공개 승격 및 정식 출시는 이 단계의 범위 밖.
5. release/readiness.json의 device_qa_passed, accessibility_review_ready 등은 실제 증빙 없이는 바꾸지 않습니다.

이번 변경은 버전 및 배포 문서 준비만 수행하며 병합, 서명 및 Play 업로드를 완료로 표시하지 않습니다.
