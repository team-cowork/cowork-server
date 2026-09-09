# 여러 VM의 배포 설정 전환

- **서비스**: 전체 운영 앱, Vault, 모니터링, VM별 로그 에이전트
- **우선순위**: 🔴 높음
- **현재 상태**: 배포 코드와 정적 검증은 준비되어 있으나 실제 Vault 등록값·VM 데이터·실행 프로세스는 확인하지 않았다
- **관련 작업**: [외부 Config Git 제거 및 prod native 전환](../09-configuration/remove-external-config-git.md), [메트릭 수집 장애 복구](../11-monitoring/metrics-collection-recovery-and-health-dashboard-removal.md)

## 문제

`deploy/prod/inventory.json`은 서비스와 Vault target만 연결한다. Actions는 Vault의 배포 문서를
읽어 같은 SHA의 이미지·스크립트에 적용한다. 실제 Vault 문서와 GitHub Environment별 읽기·쓰기
정책, runner의 Vault 접근성은 아직 확인하지 않았다.

기존 user 네이티브 프로세스, 모니터링 프로젝트, Vault 데이터 볼륨은 최초 전환이 필요하다.
공통 배포 파일 변경은 여러 서비스를 배포 대상으로 선택하므로 운영 준비 전에 자동 배포가
시작되지 않도록 전환 시점과 GitHub Environment 승인 절차를 조율한다.

## 할 일

- 서비스별 실제 VM·SSH 주소·사설 통신 주소·배포 계정을 확인한다.
- [운영 전환 절차](../../../deployment.md)에 따라 `deploy/<target>`에 SSH 접속값·사설 주소·프로파일·파일형 시크릿을 등록한다.
- `Prod-CD(<target>)`와 `Config-Update(<target>)`의 Vault 주소·최소 권한 토큰을 등록하고 `main` 브랜치 제한을 적용한다.
- 공통 시크릿은 `runtime_refs`·`application_refs`로 참조하고 기존 DSN·Config Server 속성과 credential 일치를 확인한다.
- Config Server 토큰이 배포 SSH key 경로를 읽지 못하도록 정책을 분리한다. 토큰 만료·회전과 향후 OIDC 인증 전환을 검토한다.
- 현재 `APP_CONFIG_PROFILE`을 Vault에 명시하고 `prod` 전환은 Config/Vault 설정을 준비한 뒤 순서대로 진행한다.
- Vault 봉인·중단 시 사용할 `VAULT_BOOTSTRAP_JSON`과 별도 복구 자료를 준비한다.
- DB·Vault 백업, 실제 볼륨 이름, user 네이티브 프로세스 복구 방법을 확보한다.
- user 프로세스, 모니터링 프로젝트, Vault를 각각 데이터 보존 절차에 따라 전환한다.
- 각 앱 VM의 로그 에이전트 target을 Vault·Environment·inventory에 등록하고 Alloy 적용 후 기존 Promtail을 중지한다.
- `check` 결과를 확인한 뒤 서비스 단위로 배포하며, 이후 자동 배포를 적용한다.

## 검증

- 실제 VM에서 Gateway·Config·Vault·Kafka·DB 접근과 방화벽 허용 범위를 확인한다.
- Eureka의 광고 주소가 각 VM에서 도달 가능하고, chat·roadmap의 readiness가 열리는지 확인한다.
- 기존 사용자 데이터·Vault 시크릿·Grafana 설정·Prometheus/Loki 데이터의 보존 여부를 확인한다.
- 모든 앱 VM의 로그 도착과 실패 시 이전 릴리스의 수동 복구 경로를 운영 점검으로 확인한다.

## 완료 조건

- 서비스별 VM 설정과 프로파일이 실제 배치와 일치하며 자동 배포에 필요한 준비가 완료되어 있다.
- 기존 데이터가 보존되고, 중복 네이티브 프로세스·기존 로그 수집기가 실행되지 않는다.
- 운영 접속·readiness·로그 수집·복구 절차의 확인 결과가 기록되어 있다.
