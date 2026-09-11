# 운영 설정 변경과 배포 복구

운영 서비스는 여러 VM에 나뉘어 있다. SSH 접속 주소와 서비스 간 통신 주소는 다르며,
Docker 네트워크·볼륨은 VM 사이에서 공유되지 않는다. 실제 VM 주소·기존 볼륨·프로세스 상태는
저장소만으로 확정할 수 없으므로 설정을 바꿀 때 실제 운영값과 대조한다.
이 문서는 배포 이후 설정 교체·새 target 추가·복구에 사용하는 운영 참고 자료다.

## 설정 관리 계약

**Vault가 운영값의 기준이고 GitHub Actions가 수정·배포 창구다.** VM의 `/etc/cowork/*.env`는
읽지 않는다. 클라우드 콘솔이나 VM에 접속하지 않고 Vault UI/API 또는 아래 workflow로 값을 변경한다.
저장소에는 서비스 구성과 안전한 기본 포트만 남기며 실제 주소·프로파일·계정은 Vault에서 읽는다.

| Vault KV v2 경로 (`secret` mount 기준)           | 관리하는 값                                                                                                                                                |
|--------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `deploy/<target>`                                | `ssh` 접속 주소·포트·사용자·키·검증된 host fingerprint, `runtime` 배포 주소·프로파일·인프라 접속값, `application` 컨테이너 환경변수 |
| `application`, `application/<profile>`           | Config Server가 배포하는 공통 애플리케이션 속성                                                                                                            |
| `cowork-<service>`, `cowork-<service>/<profile>` | 서비스별 속성·시크릿. 정확한 키는 [설정 가이드](configuration.md)를 따른다.                                                                                |

`target`은 VM에 배포할 단위를 식별한다. 기본값은 서비스 이름이며, `inventory.json`에는
서비스와 target의 연결만 둔다. VM 주소와 SSH 포트는 Vault에 있다. 같은 서비스의 다른 VM은
다른 target을 사용한다. 한 VM에 같은 서비스 컨테이너를 둘 이상 배치하는 구성은 지원하지 않는다.

[project 설정 양식](../deploy/prod/settings.example.json)의 값을 실제 환경으로 바꿔 `deploy/project`에
등록한다. `runtime` 값과 `application` 값은 모두 문자열이다. 주소는 인프라별로 지정하고
`ADVERTISE_IP`에는 Gateway와 모니터링 VM에서 도달 가능한 이 VM의 사설 IP를 넣는다.
`APP_CONFIG_PROFILE`은 `local` 또는 `prod`를 명시하며 묵시적 프로파일 전환은 하지 않는다.
컨테이너에 직접 넣을 추가 변수는 `application`에서 관리한다. Config Client가 읽는 일반 속성과
시크릿은 기존 `cowork-<service>` 경로를 우선 사용한다.

같은 시크릿을 여러 번 복사하지 않으려면 양식의 `runtime_refs`처럼 Vault 경로와 키를 참조한다.
`application_refs`도 같은 형식이며 해당 컨테이너 환경변수에 적용된다. 참조는 배포 시 최신 버전을
읽고 읽은 경로별 버전을 기록한다. 읽기 토큰에는 참조 경로의 `read` 권한도 필요하다.
Firebase 자격 증명은 `cowork-notification/<profile>`의 `fcm.credentials-json` 문자열로 관리한다.
Config Server가 활성 프로파일에 맞춰 전달하고 알림 서비스가 메모리에서 사용한다.
배포 문서의 `files`와 파일 마운트는 사용하지 않는다.

### GitHub Environment와 Vault 권한

배포 target과 설정 변경용 Environment는 다음 계약을 사용한다.

| Environment                | Variables                                              | Secrets                                                 | Vault 토큰 권한                                                                               |
|----------------------------|--------------------------------------------------------|---------------------------------------------------------|-----------------------------------------------------------------------------------------------|
| `Prod-CD(<target>)`        | `VAULT_ADDR` (HTTPS), `VAULT_KV_MOUNT` (기본 `secret`) | `VAULT_DEPLOY_READ_TOKEN`                               | `secret/data/deploy/<target>` 및 필요한 참조 경로의 `read`                                    |
| `Config-Update(<target>)`  | 위와 동일                                              | `VAULT_CONFIG_WRITE_TOKEN`, 변경 시 `VAULT_UPDATE_JSON` | 수정 대상의 `create`, `update`만 부여                                                         |
| `Prod-CD(vault)` 복구 전용 | 위와 동일                                              | `VAULT_BOOTSTRAP_JSON`                                  | 봉인·중단 복구 시 Vault 조회를 생략한다. 참조 없이 `ssh`와 Vault 기동용 `runtime`을 포함한다. |

GitHub에는 Vault 접근에 필요한 최소 자격 증명과 일시적인 변경 입력만 둔다. Config Server의
`VAULT_TOKEN`은 `deploy/config`에서 관리하며 `application`·`cowork-*` 속성을 읽는 별도 토큰을 쓴다.
배포 SSH 키가 있는 `deploy/*`를 Config Server 토큰에 허용하지 않는다. mount를 바꾸면 Config의
`VAULT_BACKEND`도 같은 값으로 지정한다. 복구용 자료와 unseal key는 Vault 밖에도 보관해야 한다.

Environment는 `main` 배포만 허용하도록 제한한다. Actions runner에서 Vault HTTPS에 접근할 수 있어야
하며, 이 최초 연결·정책 설정 이후 일상적인 값 교체에는 VM 설정 변경이 필요하지 않다.
VM에는 Docker Engine, Compose 2.24.4 이상, Bash, curl, flock, Git, Python 3가 필요하다.

현재 inventory의 target은 `authorization`, `channel`, `chat`, `config`, `gateway`, `monitoring`,
`notification`, `preference`, `project`, `roadmap`, `team`, `user`, `vault`, `voice`다.
GitHub 저장소의 `Settings → Environments`에서 각 `Config-Update(<target>)`과 공통 앱 속성용
`Config-Update(application)`을 만들고 `main` 브랜치 제한을 적용한다. 앱 속성용 쓰기 정책은
해당 `application[/<profile>]` 또는 `cowork-<service>[/<profile>]` 경로에만 부여한다.

`VAULT_KV_MOUNT`는 기본 `secret`과 다를 때만 등록한다. `GITHUB_TOKEN`은 Actions가 자동 발급하며,
기존 `DISCORD_INFORMATION_ALERT_CHANNEL_WEBHOOK`은 저장소 Secret에 있어 신규 등록 대상이 아니다.

Environment 생성·브랜치 제한과 토큰 발급을 마친 뒤 아래 주소·파일 경로를 실제 값으로 바꿔 등록한다.
토큰 파일은 저장소 밖에 권한 `600`으로 보관한다. 각 target에 반복하되 토큰은 해당 경로 권한으로 제한한다.
`Config-Update(application)`에는 Vault 주소와 공통 앱 속성 전용 쓰기 토큰만 등록한다.

```bash
target=project
vault_addr=https://REPLACE_WITH_VAULT_HOST
gh variable set VAULT_ADDR --env "Prod-CD($target)" --body "$vault_addr"
gh variable set VAULT_ADDR --env "Config-Update($target)" --body "$vault_addr"
gh secret set VAULT_DEPLOY_READ_TOKEN --env "Prod-CD($target)" < /secure/project-read-token.txt
gh secret set VAULT_CONFIG_WRITE_TOKEN --env "Config-Update($target)" < /secure/project-write-token.txt
gh secret set VAULT_BOOTSTRAP_JSON --env 'Prod-CD(vault)' < /secure/vault-bootstrap.json
```

## 외부에서 값 변경과 재배포

기존 `cowork prod CD Workflow` (`cowork-prod-cd.yml`)에서 자동 배포와 수동 작업을 함께 처리한다.
CI 성공 시 기존 빌드·릴리스·배포 흐름이 실행된다. 수동 실행은 `operation=update-config`로 Vault 문서를
교체하거나 `operation=redeploy`로 기존 이미지에 설정을 다시 적용한다. 수동 작업은 이미지를 새로 빌드하지 않는다.
이 변경이 `main`에 반영된 뒤 새 배포 스크립트를 포함해 빌드된 SHA를 선택한다.

자동 배포는 target마다 마지막으로 적용에 성공한 SHA부터 변경을 비교한다. GitHub Deployment의
`task=cowork-runtime` 기록은 이미지 교체와 readiness 확인이 끝난 뒤에만 생성한다.
일반 Actions Environment의 성공, `check_only=true`, 설정 변경 작업은 비교 기준을 바꾸지 않는다.
실패·취소된 배포의 변경은 다음 성공한 CI 실행에서 다시 포함하며, 이미 더 최신 SHA가 적용된
target에는 과거 자동 실행을 적용하지 않는다. 이전 SHA로 복구하려면 수동 재배포를 사용한다.
아직 기록이 없는 target은 최초 한 번 전체 적용 대상으로 선택한다. 조회 권한·API 오류는
배포 실패로 처리하며, 기록이 없다고 간주하지 않는다.

선택된 대상은 Vault → Config Server → 나머지 서비스 순으로 적용한다. 앞 단계 실패 시
다음 단계는 시작하지 않는다. 배포 기록용 `GITHUB_TOKEN`은 `deployments: write`를 사용하며
별도 운영 토큰을 추가할 필요는 없다. 기록 전송만 실패한 경우에도 workflow는 실패로 표시되고
다음 실행이 이전 성공 기준으로 다시 계산한다.

이미지 빌드 입력은 `deploy/images/catalog.json`에 등록한다. 루트 `.dockerignore`, Gradle 공통
설정과 각 모듈의 빌드 스크립트도 변경 감지에 포함한다. `cowork Docker Image CI`는 stacked PR의
부모 브랜치도 지원하며 local·prod 이미지를 게시하지 않고 빌드한 뒤 파일·사용자·로그 권한을
검사한다. 애플리케이션이나 DB는 시작하지 않는다. CD도 같은 이미지 빌드와 검사를 거친다.
BuildKit 레이어와 의존성 cache mount는 별도로 저장하며, 서비스·환경별로 캐시를 구분한다.

아래는 `project` 배포 문서를 교체하는 예다. 저장소 밖의 `project.json`을 권한 `600`으로 준비한다.
`expected_version`은 Vault에 표시된 현재 버전이며 새 경로 생성에만 `0`을 쓴다.

```bash
target=project
gh secret set VAULT_UPDATE_JSON --env "Config-Update($target)" < /secure/project.json
gh workflow run cowork-prod-cd.yml --ref main -f operation=update-config \
  -f target="$target" -f scope=deployment -f profile=base -f expected_version=3
```

workflow 성공과 출력 버전을 확인한 뒤 다음 입력을 실행한다. `sha`에는 이미 배포 이미지가 만들어진
`main`의 전체 커밋 SHA를 넣는다. 첫 실행은 `check_only=true`로 정적 설정을 확인한다.

```bash
target=project
sha=REPLACE_WITH_40_CHARACTER_MAIN_SHA
gh workflow run cowork-prod-cd.yml --ref main -f operation=redeploy \
  -f service=project -f target="$target" -f sha="$sha" -f check_only=true
# 위 실행 성공 확인 후 같은 명령에서 check_only=false로 적용
gh secret delete VAULT_UPDATE_JSON --env "Config-Update($target)"
```

애플리케이션 속성만 변경할 때는 `scope=application`, `target=project`, `profile=prod`를 사용하고
JSON에 정확한 flat property key를 넣는다. 공통 속성은 `target=application`이다. 변경은 지정한
문서 전체를 교체하므로 유지할 키도 포함한다. 버전이 다르면 쓰기를 거부한다.
[Vault CAS 규약](https://developer.hashicorp.com/vault/api-docs/secret/kv/kv-v2)을 사용하며 값은 로그에 출력하지 않는다.
변경 후 영향을 받는 앱을 같은 SHA로 재배포한다. Config Server의 접속값·프로파일을 바꾸는 경우에는
Config Server부터 배포한다. 모든 앱이 동적 refresh를 지원한다고 가정하지 않는다.

`VAULT_UPDATE_JSON`은 성공 후 삭제한다. GitHub Secret의 [48 KB 제한](https://docs.github.com/en/actions/reference/security/secrets)을
넘는 문서는 Vault UI/API에서 수정한다. 배포 snapshot은 SSH 전달을 위해 64 KiB 이하로 제한한다.

Vault 복구는 `operation=redeploy`, `service=vault`, `target=vault`, `vault_recovery=true`로 실행한다. 평상시에는 이 옵션을
사용하지 않는다. 토큰 만료 전 교체와 Vault 백업·복구 자료 관리는 운영 중에도 계속 수행한다.
향후 단기 인증 도입과 만료 알림 자동화는 [인증 자동화 TODO](todo/items/42-deployment/vault-auth-automation.md)로 분리한다.

## 기존 프로세스와 데이터 유지

| 대상       | 최초 전환 시 확인할 사항                                                                                                                                                                                                        |
|------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| user       | 기존 네이티브 Elixir 프로세스와 자동 재기동 관리자를 중지한 뒤 컨테이너로 전환한다. 기존 Flyway 이력·DB 접속값을 확인하고 네이티브 릴리스 복구 방법을 보관한다. 첫 전환에는 자동 복구할 이전 Docker 컨테이너가 없다.            |
| monitoring | 기존 Prometheus·Grafana·Loki 볼륨의 실제 이름을 확인해 `MONITORING_VOLUME_PREFIX`를 지정한다. 기존 모니터링 컨테이너만 중지하고 새 프로젝트로 기동한다. 세 볼륨의 접두사가 다르면 Compose의 명시적 `name`을 실제 이름에 맞춘다. |
| Vault      | 기존 `cowork-vault-prod`의 데이터 볼륨 이름을 `VAULT_DATA_VOLUME`에 지정한다. 기존 `vault` 프로젝트 이름을 유지한다. 기존 설치에서 새 빈 볼륨을 만들거나 local seed를 실행하지 않는다.                                          |

볼륨 이름은 컨테이너의 환경변수나 시크릿을 출력하지 않고 확인한다.

```bash
docker inspect --format '{{range .Mounts}}{{println .Destination .Name .Source}}{{end}}' cowork-vault-prod
docker ps --format '{{.Names}}'  # 기존 모니터링 컨테이너 이름 확인
# 위 inspect 명령의 컨테이너 이름을 각 Prometheus·Grafana·Loki 컨테이너로 바꿔 확인
```

Vault의 TLS 종단 프록시만 Vault 사설 포트에 접근하도록 제한한다. 최초 초기화와 unseal key
보관은 운영자가 수행한다. 현재 CD의 단일 unseal key 입력은 기존 1-share/1-threshold 환경을
전제로 하므로 다중 share 환경은 별도 unseal 절차가 필요하다.
모니터링과 Vault는 앱 자동 롤백 대상이 아니므로 업그레이드 전 백업·복구 절차를 준비한다.
기존 데이터가 있는 Compose 프로젝트에서 이름을 임의로 바꾸거나 `down -v`를 실행하지 않는다.

## 서비스별 점검과 적용

`check`는 설정 검증이며 네트워크 연결이나 서비스 기동 성공을 보장하지 않는다.
chat·roadmap은 projection readiness가 열려야 배포가 성공한다. 기존 데이터 복구 문제가 있으면
liveness로 우회하지 않고 원인을 먼저 해결한다.
readiness 기본 대기는 420초이며 `HEALTH_TIMEOUT_SECONDS`로 조정한다. 늘릴 때는 CI의
SSH `command_timeout`에 이미지 pull과 실패 후 복구 시간까지 확보한다. 상태 토픽 v2의
기존 데이터 전환은 [별도 유지보수 절차](kafka-state-topic-cutover.md)에 따라 진행한다.

별도 단일 VM 설치만 `./deploy/compose.sh single-vm-prod`를 사용한다.
`COMPOSE_ENV_FILE`은 [양식](../deploy/compose/single-vm.prod.env.example)을 채운 절대 경로,
`COMPOSE_PROJECT_NAME`은 기존 데이터 볼륨을 생성한 프로젝트 이름으로 지정한다.

local과 단일 VM prod의 앱 이미지는 UID/GID `10001`로 실행한다. `logs-init`은 공유 로그 볼륨의
소유권을 해당 사용자로 맞춘 뒤 종료하며, 로그 볼륨을 사용하는 앱은 초기화 완료를 기다린다.
JVM 로그 경로는 이미지의 `COWORK_LOG_DIR=/var/log/cowork`를 사용한다. 다중 VM CD는 각 이미지
내부의 쓰기 가능한 로그 디렉터리와 Docker 로그 수집을 사용한다.

앱 VM마다 `deploy/log-agent-<vm>` 문서를 만들고 `runtime.LOG_HOST`·`LOKI_PUSH_URL`을 지정한다.
Docker data-root가 다르면 `DOCKER_CONTAINER_LOG_DIR`도 지정한다. `Prod-CD(log-agent-<vm>)`과
`Config-Update(log-agent-<vm>)`에 위 연결 설정을 등록한 뒤 같은 수동 workflow에서 `service=log-agent`, `target=log-agent-<vm>`으로 적용한다.
주소를 확정하면 inventory에 같은 service와 서로 다른 target을 등록해 자동 변경 감지에도 포함한다.

기존 Promtail은 Alloy 전환 시 중지한다. 읽기 위치 형식이 달라 남아 있던 로그가 재전송될 수 있다.
서비스별 로그 필드 정규화와 수집 누락은 [로그 수집 TODO](todo/items/43-monitoring/log-collection-contract.md)에서 관리한다.

## 실패 복구

앱 교체에는 짧은 중단이 있다. readiness 실패 시 이전 컨테이너를 재시작하지만 DB migration과
외부 부작용은 되돌리지 않으므로 이전 이미지와 호환 가능한 스키마 변경이 필요하다.
여러 VM 배치는 복제나 무중단 배포를 보장하지 않는다.

전원 장애·SIGKILL 후 `*-candidate`·`*-previous`가 남으면 다음 배포는 중단된다.
Docker 상태와 포트 점유를 확인해 이전 컨테이너를 복원하거나 잔여 컨테이너를 정리한다.
이전 컨테이너의 IP·포트가 새 설정과 다르면 복원 후 원래 주소로 상태를 확인한다.

수동 롤백은 같은 workflow에 이전 이미지 SHA와 `configuration_version`을 지정한다.
이는 배포 문서 버전만 고정한다. `runtime_refs`·`application_refs`와 Config Server 애플리케이션
속성은 최신 값을 읽으므로 필요하면 해당 Vault 경로도 먼저 복원한다. DB 계정·외부 API key의
실제 회전은 Vault 문자열 교체만으로 수행되지 않으며 기존 키의 유효 기간과 재배포 순서를 맞춘다.
실행 중 컨테이너의 bind mount가 참조하는 릴리스·설정 파일과 복구용 snapshot은 삭제하지 않는다.
디스크 보존 정책의 자동화는 [릴리스 정리 TODO](todo/items/44-deployment/release-retention.md)로 분리한다.
