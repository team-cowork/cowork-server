# Vault 봉인과 호스트 디스크 부족 감지

- **서비스**: Vault, 모든 운영 VM, monitoring
- **우선순위**: 🟠 중간
- **현재 상태**: 슬롯의 서비스 소실 감지와 호스트 디스크 부족 알림은 구현되어 있으며 Vault seal 수집과 알림은 없다.
- **관련 작업**: [실행 중 릴리스를 보존하는 디스크 정리](../44-deployment/release-retention.md)

## 문제

서비스의 readiness와 기대 target 소실은 슬롯을 기준으로 감지한다. 이 신호만으로 Vault의 봉인이나
호스트 디스크 부족을 구분할 수는 없다.

호스트 디스크는 VM마다 배포하는 `node-agent`의 node_exporter와 `cowork-host` 알림 규칙으로 감지한다.
monitoring의 `NODE_EXPORTER_TARGETS`가 비어 있으면 호스트 지표를 수집하지 않고 `coworkHostMetricsMissing`이
계속 발화한다. Vault seal 감지 경로는 아직 없다.

## 할 일

- Vault seal의 수집·알림 경로를 마련한다.
- 현재 디스크 여유를 측정하고 필요한 정리는 릴리스 보존 작업과 함께 수행한다.

## 검증

- Vault seal·디스크 부족이 정한 시간과 경로로 감지되는지 수동 확인한다.
- 과거 장애 수치를 재사용하지 않고 실제 서비스·호스트의 관측값을 확인한다.

## 완료 조건

- Vault seal과 호스트 디스크 부족을 구분하여 감지하고 알린다.
