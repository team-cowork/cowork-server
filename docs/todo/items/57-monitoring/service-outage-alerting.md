# Vault 봉인과 호스트 디스크 부족 감지

- **서비스**: Vault, 모든 운영 VM, monitoring
- **우선순위**: 🟠 중간
- **현재 상태**: 슬롯의 서비스 소실 감지와 호스트 디스크 부족 알림은 구현되어 있다. node-agent는 log-agent와 같은 IaC per-host 단위로 14개 호스트에 배포되도록 연결했고, 9100 인그레스는 그중 10개(gateway·config 제외) application 호스트에만 자동으로 열린다. Vault 호스트(IP 미확보), gateway·config·datastore(db)의 9100 인그레스, Vault seal 수집·알림은 아직 없다.
- **관련 작업**: [실행 중 릴리스를 보존하는 디스크 정리](../44-deployment/release-retention.md)

## 문제

서비스의 readiness와 기대 target 소실은 슬롯을 기준으로 감지한다. 이 신호만으로 Vault의 봉인이나
호스트 디스크 부족을 구분할 수는 없다.

호스트 디스크는 VM마다 배포하는 `node-agent`의 node_exporter와 `cowork-host` 알림 규칙으로 감지한다.
수집 대상은 log-agent와 같은 방식으로 `deploy/prod/slots/node-agent-<host>.json`에서 derive하며(단일
출처), `deploy/iac/prepare.py`가 log-agent와 나란히 호스트마다 `node-agent-<host>` 유닛을 생성해 같은
Ansible 루프(`unit.yml`)로 적용한다 — 해당 slot이 있는 호스트만 대상이라 Vault·github-app은 자동으로
빠진다. 현재 slot은 11개 application 서비스(gateway 포함)와 datastore(db)·monitoring 호스트까지 총
14개가 있다. Vault 호스트는 전용 애플리케이션 슬롯이 없어 사설 IP를 커밋된 파일에서 끌어올 수 없으므로
아직 slot이 없다 — 실제 IP를 아는 사람이 `node-agent-vault.json`을 추가해야 한다. 그 전까지는 Vault VM의
디스크 부족이 `coworkHostDiskSpaceLow`/`Critical`로 잡히지 않는다. github-app VM은 별도 저장소
(`cowork-github-app`)가 소유하므로 이 node-agent 롤아웃 범위에서 제외한다.

9100 포트 인그레스는 `deploy/prod/slots.py`의 `firewall()`이 application 슬롯의 기존 포트와 같은 peers로
자동 허용하며, `prepare.py`가 호스트마다 설정하는 `cowork_firewall_target`을 통해 다음 IaC apply에
반영된다 — 단 gateway는 애초에 `cowork_firewall_target`이 설정되지 않는 호스트라 이 경로에 포함되지 않는다.
config는 이 함수가 아니라 별도 경로(`config-firewall.py render`)로 방화벽을 렌더링하므로 `firewall()`
출력과 무관하며, 9100을 열려면 그 스크립트를 직접 고쳐야 한다. datastore(db)·Vault처럼 슬롯이 없는
호스트는 `slots.py`가 관여하지 않는 별도 방화벽 스크립트(`datastore-firewall.py`, Vault 자체 방화벽)를
쓰므로 마찬가지로 범위 밖이다. 즉 9100 인그레스가 실제로 열리는 곳은 gateway·config를 제외한 10개
application 호스트뿐이다. Vault seal 감지 경로도 아직 없다.

## 할 일

- `node-agent-vault.json`에 실제 Vault VM 사설 IP를 채운다.
- gateway·config·datastore(db)·Vault VM에 9100 인그레스를 각자의 방화벽 경로에 맞게 추가한다
  (gateway/config는 전용 스크립트, db/vault는 각자의 방화벽 스크립트).
- Vault seal의 수집·알림 경로를 마련한다.
- 현재 디스크 여유를 측정하고 필요한 정리는 릴리스 보존 작업과 함께 수행한다.

## 검증

- Vault seal·디스크 부족이 정한 시간과 경로로 감지되는지 수동 확인한다.
- 과거 장애 수치를 재사용하지 않고 실제 서비스·호스트의 관측값을 확인한다.

## 완료 조건

- Vault seal과 호스트 디스크 부족을 구분하여 감지하고 알린다.
