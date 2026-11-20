# DT-PLAY-04 서명·테스트 배포 설정

2026-11-20 사용자 승인 범위: IARC 약관, 폭넓은 타겟 연령, 기존 업로드 키를 이용한 AAB 생성과 내부·비공개 테스트 배포. 정식 출시 승인 없음.

GalliPick의 세 단계 분리를 참고하여 수동 워크플로를 구성했다. 다른 앱의 키·서비스 계정 값을 복사하거나 새 키를 생성하지 않는다.

| 단계 | 수동 워크플로 | 동작 |
|---|---|---|
| 서명 AAB | play-bundle.yml | 동결 main 검증, 기존 키 복원, 서명·패키지·버전·해시 검증 |
| 내부 테스트 | play-internal-release.yml | 성공한 빌드의 동일 AAB 검증 후 internal 업로드 |
| 비공개 테스트 | play-closed-release.yml | 성공한 내부 릴리스의 동일 versionCode·해시를 기존 비공개 트랙에 승격 |

공통 게이트는 정확한 main SHA, 최신 main CI 성공, 명시적 단계 승인, 런타임 소스에 맞는 실제 기기 터치·안전 QA, 접근성 시연·심사 자료다. `release/readiness.json`은 현재 미검증 항목을 false/빈 값으로 유지한다. 설정 파일을 통과 목적으로 허위 변경하지 않는다. PR #1은 실기기 검증 전 Draft 상태를 유지한다.

## 기존 키 연결

- Secret: `DUALTOUCH_SIGNING_BUNDLE_BASE64`. 기존 키스토어를 담은 ZIP의 Base64이며 소스·Drive·로그에 값을 기록하지 않는다.
- ZIP: `dualtouch-signing.p12` 또는 `dualtouch-signing.jks` 1개, `signing.properties`, 공개 `certificate.sha256`, `RESTORE.txt`.
- private properties: storeFile, storeType, storePassword, keyAlias, keyPassword. GalliPick과 같은 아카이브 구조이며 다른 앱의 인증서 pin을 임의 재사용하지 않는다.
- 공개 인증서 SHA-256은 기존 키에서 확인한 값만 `release/readiness.json`의 `upload_certificate_sha256`에 기록한다. 현재 미확인.
- Play 인증은 기존 `PLAY_WORKLOAD_IDENTITY_PROVIDER` + `PLAY_SERVICE_ACCOUNT`를 우선하고, 기존 `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`이 있을 때만 대체한다. DualTouch에 대한 실제 권한 확인 필요.
- Secrets는 API에서 읽어 되가져올 수 없다. 이 연결의 GitHub 도구에는 Secrets 관리 기능도 없다. 다른 저장소에서 사용 중이라는 사실만으로 DualTouch에서 이용 가능하다고 간주하지 않는다.

## 첫 업로드와 미완료 사항

DualTouch Console 앱 ID: 4972460737161353707, package: com.hebi.dualtouch. 현재 AAB 최초 업로드·Play App Signing 설정·테스터·비공개 트랙은 미확인이다. API가 앱/트랙을 찾지 못하면 자동 생성하거나 다른 트랙으로 대체하지 않는다. 최초 등록과 심사 필수 항목은 Console에서 완료한다.

현재 실기기 핵심 입력/중단 QA, 접근성 영상, 기존 키 및 Play 인증이 확인되지 않아 서명·업로드·비공개 게시 완료로 보고할 수 없다. API commit/readback, Console 검토 상태, 테스터 제공 상태를 별도로 확인한다. 일반 PR/main CI에서는 AAB나 Play 업로드를 실행하지 않는다.

## 검증 범위

릴리스 단위 테스트는 미승인·브랜치·main 변경·실기기 증빙 누락·런타임 변경·서명 pin 누락·잘못된 빌드 출처·AAB 해시 불일치·미완료 internal·프로덕션 트랙 접근을 차단하는 경계를 확인한다. 실제 서명/AAB/Play API 배포 시험을 대체하지 않는다.
