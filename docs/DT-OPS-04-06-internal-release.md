# DualTouch OPS-09 내부 테스트 배포

- 사전 조건은 DualTouch 전용 Play 서비스 계정, Android Publisher API, WIF와 `google-play` 환경의 `PLAY_WORKLOAD_IDENTITY_PROVIDER`, `PLAY_SERVICE_ACCOUNT` 변수입니다. Play Console의 앱 범위에서 조회·테스트 출시를 설정합니다. 테스트 출시 권한의 트랙 범위와 별개로 이 실행 경로는 `internal`만 사용합니다.
- 인증을 먼저 확인하려면 `play-auth-preflight.yml`을 같은 main SHA에서 수동 실행합니다. 해당 점검은 임시 edit를 삭제하며 Play에 출시를 생성하지 않습니다. Console에서 편집 중인 릴리스가 없는지 확인합니다.
- 사용자 배포 승인과 해당 SHA의 Android CI 성공을 확인한 뒤 `internal-release-once.yml`을 `main`에서 실행합니다. `expected_main_sha`, `expected_version_code`, `internal_release_approved`를 지정합니다. 연결된 채팅에서 실행할 때도 동일한 GitHub Actions 수동 실행을 사용합니다.
- 기존 `internal-qa-bundle.yml`이 등록된 업로드 키로 서명합니다. 게시 작업은 동일 실행의 AAB·서명 인증서·패키지·버전·내용 해시를 재검증합니다. Play에서 같은 버전이나 더 높은 버전 또는 내부 릴리스 초안이 확인되면 중단합니다.
- Actions 영수증에는 소스 SHA, 버전, AAB SHA-256, Play commit 및 재조회 결과가 들어갑니다. Console에서 테스터에게 제공 가능한지 확인한 뒤 Fold8·태블릿 QA를 진행합니다.
