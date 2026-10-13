# Config Server 기존 Vault 갱신 cron과 토큰 폐기

- **서비스**: cowork-config, Vault VM, 운영 배포
- **우선순위**: 🟠 중간
- **현재 상태**: 새 Vault의 AppRole과 기존 갱신 토큰의 분리를 확인했으며, 새 Config 실제 전환 후 기존 cron·토큰 폐기가 남아 있다.

> **2026-10-13 관측:** DB VM의 `ubuntu` crontab에 UTC 기준 `0 */6 * * *` 갱신 작업이 있다. 스크립트는 `/home/ubuntu/renew-config-vault-token.sh`, 토큰 파일은 `/home/ubuntu/.cowork-config-vault-token`, 로그는 `/home/ubuntu/renew-config-token.log`다. 갱신 토큰은 기존 Vault에서 유효하고 새 Vault에서는 거부된다. 새 `deploy/config`에는 `VAULT_TOKEN`과 해당 참조가 없으며 AppRole을 사용한다. 새 Config 배포는 GHCR 인증 단계에서 중단되어 아직 기동하지 않았다.

## 문제

기존 DB VM의 cron은 `cowork-vault-prod` 컨테이너에서 `period=24h` 토큰을 6시간마다 갱신한다.
갱신 토큰은 기존 Config 컨테이너의 `VAULT_TOKEN`과 다르다. 새 Config VM과 새 Vault VM에는
관련 cron·systemd 예약 참조를 찾지 못했다.

Config Server는 `APPROLE` 로그인·갱신·재로그인을 사용하도록 변경했다. 운영 검증 전에 기존 갱신
작업과 토큰을 폐기하면 이전 버전으로 복구할 때 사용할 인증 수단을 잃을 수 있다. 전환 확인 뒤
사용하지 않는 갱신 작업, 토큰, 자격 증명 사본과 참조를 함께 정리한다.

## 범위와 선행 조건

정책·자격 증명 준비와 전환 절차는 [Config Server 접근 보호 운영](../../../config-server-access.md)을 따른다.
이번 작업은 기존 클러스터에서의 연속 인증 전환이 아니라 승인된 별도 Vault 재구성이다.
기존 토큰을 복구 수단으로 사용하는 7일 병행 운영 조건은 이번 폐기의 선행 조건으로 적용하지 않는다.
새 Config의 실제 설정 조회와 AppRole 로그인을 확인하고, 새 클러스터의 복구 자료를 보존한 뒤
기존 전용 갱신 작업을 폐기한다. 새 AppRole의 갱신·재로그인 검증은 별도로 남는다.
배포 검증기의 임시 토큰 갱신 성공과 `/actuator/health` 성공만으로 실제 전환을 판정하지 않는다.

폐기 범위는 이전 Config Server의 정적·주기 토큰과 그 갱신 작업으로 제한한다.
현재 AppRole의 `VAULT_ROLE_ID`·`VAULT_SECRET_ID`, 실행 중인 서버의 토큰, GitHub Actions의
`VAULT_DEPLOY_READ_TOKEN`·`VAULT_CONFIG_WRITE_TOKEN`, Vault 복구 자료는 유지한다.

## 할 일

- Vault VM에서 기존 갱신 작업의 등록 위치, 실행 계정, 호출 스크립트와 자격 증명 참조를 확인한다.
- 새 Config의 AppRole 로그인과 실제 설정 조회를 확인하고 기존 클러스터와 복구 자료가 분리되어 있는지 기록한다.
- 복구할 릴리스와 필요한 인증 수단을 확인하고, 폐기한 토큰에 의존하지 않는 복구 절차를 준비한다.
- 기존 토큰을 갱신하는 cron 등록과 중복 예약 작업을 제거한다.
- 기존 Config 전용 토큰을 식별하고 Vault에서 폐기한다. 다른 용도의 토큰은 폐기하지 않는다.
- 해당 작업만 사용하는 스크립트·환경 파일·토큰 사본을 확인하고 제거한다.
- 운영 Config 배포 문서의 `runtime.VAULT_TOKEN`·`runtime_refs.VAULT_TOKEN` 등 잔여 참조와 이전 snapshot의 복구 가능 상태를 정리한다.
- 이전 수동 토큰 교체 알림과 운영 문서의 잔여 절차를 정리한다.

## 검증

- 폐기 전에 새 Config의 AppRole 로그인과 실제 설정 조회, 새 클러스터 복구 자료를 확인한다.
- cron 제거 후 이전 갱신 주기인 6시간을 넘겨 해당 작업이 다시 실행되지 않는지 확인한다.
- 폐기한 토큰의 인증이 거부되고, 현재 AppRole의 갱신·재로그인과 서비스 설정 조회가 유지되는지 확인한다.
- GitHub Actions 배포 인증과 현재 AppRole 자격 증명이 정리 대상에 포함되지 않았는지 확인한다.
- 자격 증명과 Config 응답 본문을 로그·문서·CI 출력에 남기지 않는다.
- 운영 수동 확인으로 검증하며 회귀·통합 테스트를 추가하거나 실행하지 않는다.

## 완료 조건

- 이전 Config 토큰을 갱신하는 예약 작업이 실행되지 않는다.
- 이전 정적·주기 토큰이 폐기되어 있으며 불필요한 자격 증명 사본과 참조가 제거되어 있다.
- Config Server는 기존 cron 없이 AppRole로 설정 조회와 인증 수명 관리를 유지한다.
- 운영 확인 결과와 폐기한 토큰에 의존하지 않는 복구 절차가 기록되어 있다.

## 점검 근거 (2026-10-08)

- 관련 PR: [#453](https://github.com/team-cowork/cowork-server/pull/453).
- [대조 코드](../../../../cowork-config/src/main/resources/application.yml): AppRole 구현은 있으며 PR은 기존 cron·토큰 폐기를 수행하지 않았다고 명시한다. 마지막 Config runtime SHA는 AppRole 이전이다.
- 판정: **운영 정리**. 새 클러스터로 실제 전환한 뒤 기존 cron·토큰·참조를 폐기한다.
