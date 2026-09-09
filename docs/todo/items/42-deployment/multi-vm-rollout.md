# 여러 VM의 배포 설정 전환

- **서비스**: 전체 운영 앱, Vault, 모니터링, VM별 로그 에이전트
- **우선순위**: 🔴 높음
- **현재 상태**: 배포 코드와 정적 검증은 준비되어 있으나 실제 VM의 환경 파일·데이터·실행 프로세스는 확인하지 않았다
- **관련 작업**: [외부 Config Git 제거 및 prod native 전환](../09-configuration/remove-external-config-git.md), [메트릭 수집 장애 복구](../11-monitoring/metrics-collection-recovery-and-health-dashboard-removal.md)

## 문제

`deploy/prod/inventory.json`은 기존 서비스별 SSH 포트와 `local` 프로파일을 유지한다.
새 배포기는 VM별 환경 파일과 같은 SHA의 이미지·스크립트를 사용하지만, 실제 운영 VM에 해당
환경 파일이 준비되어 있는지는 저장소만으로 확인할 수 없다.

기존 user 네이티브 프로세스, 모니터링 프로젝트, Vault 데이터 볼륨은 최초 전환이 필요하다.
공통 배포 파일 변경은 여러 서비스를 배포 대상으로 선택하므로 운영 준비 전에 자동 배포가
시작되지 않도록 전환 시점과 GitHub Environment 승인 절차를 조율한다.

## 할 일

- 서비스별 실제 VM·SSH 주소·사설 통신 주소·배포 계정을 확인한다.
- [운영 전환 절차](../../../deployment.md)에 따라 `/etc/cowork/common.env`와 서비스별 파일을
  준비하고, `ADVERTISE_IP`·Config·Kafka·DB·S3·LiveKit 주소와 필수 시크릿을 확인한다.
- `APP_CONFIG_PROFILE`의 VM 파일 우선순위를 고려해 현재 프로파일을 유지하며, `prod` 전환은
  Config/Vault 설정을 준비한 뒤 순서대로 진행한다.
- DB·Vault 백업, 실제 볼륨 이름, user 네이티브 프로세스 복구 방법을 확보한다.
- user 프로세스, 모니터링 프로젝트, Vault를 각각 데이터 보존 절차에 따라 전환한다.
- 각 앱 VM에 Alloy를 적용하고 기존 Promtail을 중지한다.
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
