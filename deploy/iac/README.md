# 운영 IaC

[운영 CD](../../.github/workflows/cowork-prod-cd.yml)의 `apply-infrastructure` 단계는 기존 VM에 host 정책과 모니터링을 적용하고
Terraform으로 Grafana 객체를 관리한다. VM에는 Docker Engine·Python 3와 검증된 SSH 접속 및
관리자 권한이 먼저 필요하다. Compose plugin은 [고정 도구 명세](./container-tools.json)에 따라 IaC가 관리한다.
GSM SV의 VM·사양·공개 연결은 관리하지 않는다.

## 인증과 비밀

Vault의 최초 신뢰 설정은 관리자가 외부 관리자 토큰을 `VAULT_TOKEN`으로 주입하여
[`bootstrap-vault.py`](./bootstrap-vault.py)를 적용한다. 신뢰 조건은 [`vault-auth.json`](./vault-auth.json)에 둔다.
bootstrap은 앱 CD의 슬롯 읽기·설정 쓰기 정책도 갱신한다. 비밀 참조 경로를 바꾸면 해당 정책을 먼저 반영한다.
IaC 실행은 GitHub OIDC로 인증하고 최대 20분 읽기 토큰을 사용한다. 관리자 토큰은 CI에 전달하지 않는다.
Terraform state 암호화 키는 GitHub `Prod-IaC` Environment의 `IAC_STATE_ENCRYPTION_KEY`로 공급하고
복구 사본을 관리 대상 VM·Vault 밖에 보관한다. 값은 32바이트 키의 Base64 문자열이다.
모니터링 알림 주소는 같은 Environment의 `DISCORD_MONITORING_ALERT_WEBHOOK`으로 공급한다.

Grafana는 SSH 터널로 접근한다. 인증 값은 프로세스 환경으로 전달하며 Terraform 입력·state에 넣지 않는다.
실행 runner의 평문 state·plan·Vault snapshot·SSH 자료는 저장소 밖의 비공개 임시 디렉터리에서만 사용한다.

## 초기화와 복구

MySQL·PostgreSQL·MongoDB exporter 계정은 첫 적용과 비밀번호 교체 때 관리자가 DB VM에서
[`bootstrap-exporters.py`](./bootstrap-exporters.py)로 구성한다. DB 관리자 자료와 모니터링 비밀을
비공개 표준 입력으로 전달한다. 비밀번호는 24바이트 난수의 48자리 소문자 16진수로 준비하고,
DB 계정과 Vault의 모니터링 비밀을 맞춘 뒤 IaC를 적용한다. CI에는 DB 관리자 자료를 전달하지 않는다.

인프라만 재적용할 때는 운영 CD에서 `operation=redeploy`, `service=infrastructure`를 선택한다.
첫 state 생성은 같은 수동 실행의 `initialize_state`로 명시한다. 기존 Grafana 객체는 import하거나
이번 전환에서 승인된 초기화를 수행한다. state를 읽을 수 없다는 이유로 새 state를 만들지 않는다.
로컬 적용도 워크플로와 같은 `infra-state` 브랜치와 `infra-state-lock` 잠금을 사용한다.

state 저장이 실패하면 잠금과 암호화된 복구 파일을 보존한다. 실패한 실행과 Terraform 프로세스가
종료되었는지 확인하고, 복구 파일을 같은 키로 해독하여 실제 Grafana 변경과 state가 일치하는지 대조한다.
복구한 state를 다시 암호화해 `infra-state`에 저장한 뒤 해당 실행이 소유한 잠금만 해제한다.
실행이 살아 있거나 state 일치 여부가 불명확하면 잠금을 해제하지 않는다.

자원 소유권, 비밀 교체, 실패 복구의 제약은 [운영 문서](../../docs/deployment.md)를 따른다.
`check_only` 성공은 실제 SSH 연결·수집·대시보드 동작의 확인을 대신하지 않는다.
자동 실행은 마지막 성공 적용 이후 IaC 소스가 변경된 경우에만 적용한다. 이 판단은 서버 drift를
검사하지 않는다. 비밀 교체나 서버 상태 복구 후 재적용은 수동 실행으로 수행한다.
수동 앱 재배포는 Git 슬롯과 IaC 파일을 포함한 SHA부터 지원한다. 슬롯 전환 이전 SHA는 지원하지 않는다.
