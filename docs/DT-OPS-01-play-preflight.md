# DT-OPS-01 — Play 배포 인증 사전점검

- 현재 앱: com.hebi.dualtouch / 내부 QA 후보 0.0.4-poc(4).
- 2026-11-25 기준 기존 main CI 성공: https://github.com/hebi10/DualTouch/actions/runs/45526951493
- 기존 internal-qa-bundle.yml 및 Play 내부/비공개 워크플로를 확인했다. 실제 WIF 설정/Play 계정 등록 및 쓰기 권한은 아직 확인하지 못했다.
- 제안: 공통 Cloud 프로젝트 hebi-app-release-80847404에 DualTouch 전용 play-dualtouch 계정. 신규 생성 전 Cloud와 Play Console 전체 사용자에서 기존 계정을 검색한다. 저장소 ID 1463696857, 소유자 ID 80847404와 main ref, google-play 환경에 제한한다. 실제 OIDC subject/mapping 및 환경을 확인한 뒤 WIF 구성한다. 다른 앱의 인증 키를 가져오지 않는다.
- GitHub google-play 환경의 PLAY_WORKLOAD_IDENTITY_PROVIDER / PLAY_SERVICE_ACCOUNT를 기존 실제 값과 대조한다. 자격증명은 출력하지 않는다.
- 신규 play-auth-preflight.yml: 사용자 승인과 exact main 확인 후 WIF 교환 및 해당 앱의 트랙·AAB·APK 인벤토리를 조회하고 임시 미커밋 edit를 삭제한다. 진행 중인 수동 Play Console edit와 충돌할 수 있으므로 배포/편집 중에는 실행하지 않는다. 기존 배포와 동일한 concurrency group을 사용한다.
- Preflight 성공은 인증 및 읽기 증빙만 의미한다. 실제 업로드 권한과 게시 가능 여부는 별도 검증하며, 모든 과거 versionCode의 사용 여부는 Play Console에서도 확인한다. AAB 생성·업로드·트랙 변경·commit을 하지 않는다.
- DT-OPS-02~03 Cloud/Play 앱별 권한과 WIF 설정은 연결 환경에서 확인한다. DT-OPS-04~06 내부 QA 선배포와 OPS-09를 분리 구현·검증한 후 명시 승인으로 내부 트랙만 배포한다. 일반 CI나 PR에서 게시하지 않는다. 비공개 및 production, readiness.json은 변경하지 않는다.
