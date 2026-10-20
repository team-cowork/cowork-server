# Vault 사설 TLS 전환 머지 후 작업

Config가 공개 프록시 대신 Vault 사설 TLS 리스너(`https://10.0.0.91:8210`)로 직접 AppRole 로그인하고, CD가 배포마다 AppRole secret_id를 새로 발급하고 이전 것을 회수하도록 바꾼 변경의 운영 후속 절차입니다. 다른 에이전트가 이 문서만 보고 이어서 진행할 수 있도록 현재 상태, 실행 시점, 명령, 검증 기준을 모두 적습니다.

## 실행 시점

- 이 PR을 `develop`에 머지할 때는 아무것도 하지 않습니다. Prod CD는 `main`의 `Cowork Prod CI Workflow`가 끝난 뒤에만 실행됩니다.
- 릴리즈 PR(`develop` → `main`)을 머지하면 Prod CI 다음에 Prod CD가 자동으로 실행됩니다. CI가 도는 동안 끼어들어 할 작업은 없습니다.
- 이 변경은 `.github/workflows/cowork-prod-cd.yml`, `deploy/prod/cd/`, `deploy/prod/configuration.json`, `deploy/prod/slots.py`를 건드리므로 `deploy/prod/cd/build-matrix.py` 기준 모든 대상이 재배포됩니다. 순서는 `deploy-vault` → `apply-infrastructure` → `deploy-config` → 나머지 애플리케이션입니다.
- 아래 작업은 **그 Prod CD 실행이 성공한 뒤** 진행합니다. 현재 Vault는 과도기 설정이라 서두를 필요는 없지만, 2단계를 1단계보다 먼저 하면 안 됩니다.

## 머지 전에 이미 적용한 상태 (2026-10-20)

운영 Vault(`cowork-vault`, `10.0.0.91`)에는 이전 코드와 새 코드가 모두 동작하도록 추가 변경만 적용해 두었습니다.

| 항목 | 현재 상태 |
|---|---|
| Vault 컨테이너 | `~/.local/share/cowork/manual/vault-private-tls`의 compose와 `server.hcl`로 재생성됨. 포트 `10.0.0.91:8200`(평문, 프록시용)과 `10.0.0.91:8210`(TLS) |
| TLS 인증서 | `cowork-vault-tls` 볼륨, 90일 leaf, 만료 30일 전부터 `cowork-vault-tls.timer`가 매일 갱신하고 SIGHUP으로 reload |
| CA | `/etc/cowork/vault-tls/ca.key`(root 0600), 사본은 운영자 PC에만 있음. CA는 nameConstraints로 `10.0.0.91`만 서명 가능 |
| 방화벽 | `deploy/prod/vault/firewall.json` 적용. 8200/8210은 `10.0.0.117`(프록시), `10.0.0.137`(monitoring), `10.0.0.169`(Config)만 허용 |
| audit device | `file/`(`file_path=stdout`) 활성. Docker 로그로 남고 log-agent가 Loki로 보냄 |
| `prod-settings-config` policy | 기존 경로 유지 + `auth/approle/role/cowork-config-prod/{role-id,secret-id,secret-id-accessor/destroy}` 권한 추가 |
| AppRole `cowork-config-prod` | `secret_id_bound_cidrs`, `token_bound_cidrs` = `[10.0.0.117/32, 10.0.0.169/32]` (과도기) |
| secret_id | 2026-10-13에 만든 기존 1개(프록시 `.117` 바인딩)만 존재. 지금 실행 중인 Config가 사용 중 |

검증 완료: Config VM에서 CA로 8210 TLS 검증 200, TLS 경유 AppRole 로그인 성공(token `bound_cidrs`가 `10.0.0.169`), 공개 경로 200, User VM에서 8200/8210 차단, `renew-now` 후 재시작 없이 새 인증서 제공.

## 1단계: Prod CD 결과 확인

릴리즈 Prod CD에서 다음을 확인합니다.

- `deploy-vault`: `deploy/prod/services/vault.sh`가 `backend-firewall check-binding`(8200, 8210)과 `cowork-vault-tls.py check`를 통과하고 release 디렉터리의 compose로 Vault를 재생성한 뒤 unseal해야 합니다. 이 단계의 settings에서 `::warning title=Vault recovery settings::` 경고가 나오면 `VAULT_BOOTSTRAP_JSON`이 Vault 값과 다르다는 뜻이니 기록해 둡니다.
- `deploy-config`: settings 단계가 새 secret_id를 발급하고(`cidr_list`/`token_bound_cidrs` = `10.0.0.169/32`), Config가 `VAULT_HOST=10.0.0.91`, `VAULT_PORT=8210`, `classpath:vault-tls-ca.p12`로 기동해야 합니다. 기존 secret_id는 애플리케이션 배포까지 모두 성공한 뒤 별도 job `Retire Superseded AppRole Secret IDs`가 회수합니다. 앱 배포가 하나라도 실패하면 회수하지 않으므로 restore가 올리는 이전 Config도 기존 secret_id로 로그인할 수 있습니다. 회수 자체가 실패하면 `::warning title=AppRole secret ID retirement::` 경고만 남고 다음 성공 배포에서 다시 회수합니다.
- monitoring 재배포 후 Prometheus의 `vault-tls` job이 `probe_success == 1`이어야 합니다.

Config 배포가 실패하면 이 문서의 이후 단계를 진행하지 않습니다. 과도기 상태에서는 기존 Config가 계속 동작합니다. 실패 원인을 고친 뒤 다시 배포합니다.

회수 결과는 Vault VM에서 확인합니다. 관리자 token은 `read -rs`로만 받고 `docker exec`에는 `-e VAULT_TOKEN`으로 이름만 넘깁니다. `docker exec` 인자는 `docker events`에 남습니다. 결과가 1이어야 합니다.

```bash
read -rs VAULT_TOKEN && export VAULT_TOKEN
docker exec -e VAULT_TOKEN -e VAULT_ADDR=http://127.0.0.1:8200 cowork-vault-prod \
  vault list -format=json auth/approle/role/cowork-config-prod/secret-id | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))'
unset VAULT_TOKEN
```

## 2단계: 최종 Vault 정책 적용

반드시 1단계에서 새 Config 배포와 secret_id 회수가 성공한 뒤 실행합니다. 이 스크립트는 AppRole CIDR을 `10.0.0.169/32`만 남깁니다. 먼저 실행하면 아직 프록시(`.117`)로 로그인하는 기존 Config가 재로그인하지 못합니다.

`main` 체크아웃에서 실행합니다. token은 argv나 채팅에 넣지 않고 `read -rs`로만 받습니다.

```bash
git switch main && git pull --ff-only
read -rs VAULT_TOKEN && export VAULT_TOKEN
VAULT_ADDR=https://cowork-vault.https.gsmsv.site python3 deploy/iac/scripts/bootstrap-vault.py
unset VAULT_TOKEN
```

적용 내용: 각 settings 대상별 최소 권한 policy 재작성(`prod-settings-config`에서 레거시 `secret/data/control-plane/prod/config-vault` 경로 제거), audit device 확인, AppRole `cowork-config-prod`의 CIDR을 Config slot의 `advertise_address/32`로 확정. 출력 마지막 줄은 `Applied scoped GitHub deployment, settings, IaC and Config workload policies`입니다.

검증: Vault VM에서 1단계와 같은 방식으로 token을 넣고 다음을 실행합니다. 두 CIDR 필드가 `10.0.0.169`만 포함해야 하고, Config 로그에 Vault 인증 오류가 없어야 합니다.

```bash
docker exec -e VAULT_TOKEN -e VAULT_ADDR=http://127.0.0.1:8200 cowork-vault-prod \
  vault read -format=json auth/approle/role/cowork-config-prod |
  python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; print(d["secret_id_bound_cidrs"], d["token_bound_cidrs"])'
```

## 3단계: 쓰지 않는 설정 삭제

2단계 이후에 삭제합니다.

- GitHub Environment `Prod-CD(config)`의 secret `VAULT_ROLE_ID`, `VAULT_SECRET_ID`
- 같은 Environment의 variable `VAULT_EXTERNAL_HOST`, `VAULT_PORT` (`VAULT_APP_ROLE_PATH`, `VAULT_BACKEND`, repository variable `VAULT_ADDR`, `VAULT_KV_MOUNT`는 계속 사용하므로 지우지 않습니다)
- Vault KV `secret/control-plane/prod/config-vault`. 모든 버전과 메타데이터를 지우므로 되돌릴 수 없습니다. Vault VM에서 1단계와 같은 방식으로 token을 넣고 실행합니다.

  ```bash
  docker exec -e VAULT_TOKEN -e VAULT_ADDR=http://127.0.0.1:8200 cowork-vault-prod \
    vault kv metadata delete secret/control-plane/prod/config-vault
  ```

삭제 전에 `main`에서 `deploy/prod/configuration.json`의 config 대상에 위 secret과 variable이 없는지 확인합니다. `VAULT_ROLE_ID`, `VAULT_SECRET_ID`라는 이름은 `deploy/prod/slots/config.json`의 AppRole 참조와 `deploy/prod/config/validate-config-vault-token.py`의 런타임 환경변수로 계속 쓰이며, 이는 CD가 배포마다 발급한 값입니다.

## 4단계: Vault recovery drift 점검

Actions에서 `Cowork Prod CD Workflow`를 `service=vault`, `check_only=true`로 수동 실행합니다. `::warning title=Vault recovery settings::`가 없으면 `VAULT_BOOTSTRAP_JSON`이 현재 Vault와 일치합니다. 경고가 있으면 경고에 나온 필드 이름만 보고 secret 값을 갱신합니다. 값은 로그에 출력되지 않습니다.

## 5단계: 수동 staging 정리

1단계 `deploy-vault`가 성공해 컨테이너가 release 디렉터리 기준으로 재생성된 뒤에만 지웁니다. 지금 컨테이너가 staging의 `server.hcl`을 bind mount하고 있어서 먼저 지우면 Vault가 재시작하지 못합니다.

```bash
docker inspect cowork-vault-prod --format '{{index .Config.Labels "com.docker.compose.project.working_dir"}}'
```

출력이 `~/.local/share/cowork/releases/<sha>`이면 `~/.local/share/cowork/manual/vault-private-tls`를 삭제합니다. `/root/cowork-staging/vault-tls`와 `/root/cowork-staging/backend-firewall`은 재설치용이며 CA key는 이미 제거했습니다.

## 주의와 롤백

- 이 변경 이전 커밋으로 Config를 재배포하면 검증 단계에서 실패하고 배포되지 않습니다. 이전 코드는 삭제된 GitHub secret과 KV를 기대하기 때문입니다. 롤백이 필요하면 새 커밋으로 되돌립니다.
- Vault를 다른 경로로 재생성할 때는 `VAULT_TLS_HOST_PORT=8210`, `VAULT_TLS_VOLUME=cowork-vault-tls`를 빠뜨리면 compose가 거부합니다.
- 인증서 갱신이 실패하면 `coworkVaultTlsRenewalOverdue`(만료 21일 이내), `coworkVaultTlsCertificateExpiring`(7일 이내) 알림이 Discord로 갑니다. 수동 갱신은 Vault VM에서 `sudo python3 -I /usr/local/libexec/cowork-vault-tls.py renew-now`입니다.
- CA는 2036-10-17에 만료됩니다. 교체할 때는 `deploy/config/vault/tls-ca.crt`, `cowork-config/src/main/resources/vault-tls-ca.p12`(비밀번호 `cowork-vault-tls-ca`), `/etc/cowork/vault-tls/ca.{key,crt}`를 함께 바꿉니다.

## 남은 위험

- 프록시(`10.0.0.117`) → Vault 8200 구간은 평문입니다.
- 만료되지 않는 root token이 아직 살아 있습니다.
- `docker exec` 인자는 `docker events`에 남으므로 token이나 unseal key를 명령 인자로 넘기지 않습니다. `-e VAR` 형태로 환경변수만 넘깁니다.
