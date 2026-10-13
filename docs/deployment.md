# 운영 설정 변경과 배포 복구

운영은 `deploy/prod`의 VM별 배포 경로를 사용한다. 실제 VM·Vault·DB·DNS·방화벽 상태는
저장소만으로 확정할 수 없으므로 적용 전에 운영값과 대조한다. 기존 데이터의 유지·복구·이관 여부는
운영 담당자 재량이며 배포의 필수 조건이 아니다.

## 최초 설치와 target 추가

서비스와 target의 연결은 [`inventory.json`](../deploy/prod/inventory.json), 배포 문서 형식은
[`settings.example.json`](../deploy/prod/settings.example.json)을 기준으로 한다. 실제 SSH·runtime 값은
Vault `secret/deploy/{target}`에 저장한다. 운영 VM의 환경 파일을 직접 수정하지 않는다.

1. VM의 Docker·Compose `2.24.4` 이상·Bash·curl·flock·Git·Python 3와 배포 계정 권한을 준비한다.
2. SSH 공개키·신뢰한 host fingerprint·GHCR 이미지 pull·불변 SHA 소스 다운로드를 확인한다.
3. 실제 DB·Kafka·Redis·S3·Elasticsearch·LiveKit을 준비한다. 이 인프라의 VM 생성·업그레이드는
   앱 배포 inventory가 수행하지 않는다. Kafka는 bootstrap 주소뿐 아니라 모든 advertised listener에
   앱이 도달할 수 있어야 하며, producer 기동 전에 토픽을 준비한다.
4. Vault KV v2·TLS·초기화·unseal 자료와 아래 Environment를 준비한다. Config Server 연결·계정·
   방화벽은 [별도 전환 절차](./config-server-access.md)를 따른다.
5. 공개 프록시의 HTTP·WebSocket upgrade·SSE를 확인한다. SSE buffering·idle timeout과 LiveKit의
   signaling·RTC·TURN 경로를 별도로 확인한다. `livekit-cloud.yaml`은 고정 참고 파일이므로 Compose
   환경변수만으로 key·IP가 교체됐다고 판단하지 않는다.
6. Vault → Config/Eureka → 앱 → projection 동기화 순서로 준비하고 실제 readiness를 확인한 뒤
   공개 트래픽을 연다. 앱 배포 wave 안에서는 업무 의존성별 순차 기동을 보장하지 않는다.

외부 앱 HTTP는 Gateway로 들어오고 downstream 포트는 사설 주소와 허용 peer로 제한한다.
Docker 네트워크·볼륨은 VM 사이에 공유되지 않는다. LiveKit 미디어는 별도 공개 경로가 필요하다.

같은 VM에 같은 서비스의 여러 target을 배치하는 구성은 지원하지 않는다. 배치·실제 포트는 Vault와
대조한다. 필요한 외부 볼륨은 배포 전에 생성한다. Vault의 TLS 프록시만 사설 listener에 접근시키고,
현재 단일 unseal key 입력과 다른 다중 share 구성은 별도 복구 절차를 준비한다.

## 비공개 GHCR 이미지 인증

Config와 앱 배포 job은 `packages: read` 권한의 단기 `github.token`으로 GHCR에 로그인한다.
인증 정보는 SSH 실행별 `0700` 임시 `DOCKER_CONFIG`에만 저장하고 종료 시 제거한다. 토큰을
Vault 배포 문서나 컨테이너 환경에 넣지 않으며 VM의 기존 Docker 인증 파일도 변경하지 않는다.
기존 `GHCR_READ_TOKEN` 설정을 사용하는 경우에도 Docker 로그인 결과는 같은 임시 디렉터리에 기록한다.
`check_only`는 registry 로그인과 이미지 pull을 수행하지 않으므로 이미지 접근 성공을 보장하지 않는다.

인증 전달과 설정 검증은 `github.workflow_sha`의 배포 실행기가 담당한다. 앱의 `deploy.sh`와 서비스
스크립트는 요청한 릴리스 SHA에서 실행한다. 이전 SHA를 수동 재배포해도 최신 실행기가 임시 인증
경로를 전달한다. 같은 배포 실행에서 readiness 실패 후 보존한 이전 컨테이너를 재시작하는 복구는
이미지 pull을 수행하지 않는다.

## 운영 오브젝트 스토리지

운영 스토리지의 기준은 SeaweedFS 4.47이다. 전용
[`deploy/prod/storage`](../deploy/prod/storage/README.md) 구성으로 storage·admin·`s3_lifecycle` worker를
함께 설치한다. 최초 VM 준비 이후에는 `prod-cd`의 `storage` target이 선택한 GitHub SHA와 Vault
배포 문서로 갱신한다. 상세 절차는 [운영 스토리지 GitHub CD](#운영-스토리지-github-cd)에 정의한다.

서비스별 키, 공개 아이콘 정책, 비공개 프로필·채팅 파일 정책은
[`s3-config.json.template`](../deploy/config/seaweedfs/s3-config.json.template)을 기준으로 한다.
[`configure-bucket.sh`](../deploy/config/seaweedfs/configure-bucket.sh)로 private 버킷 ACL·정책·CORS와
임시 업로드 세 접두사의 1일 만료를 적용한다. SeaweedFS 4.47의 PublicAccessBlock은 미지원이므로
그 기능의 적용 성공을 기록하지 않는다. 서비스의 객체·버킷 ACL 변경은 별도 권한 부재와 명시적
버킷 정책으로 거부한다. worker 준비 상태와 실제 만료 작업 완료를 구분하여 확인한다.

외부 파일 요청은 Gateway의 공개 API 경로로만 받는다. 사설 S3 9000 포트는 Gateway·Team·User·Chat과
스토리지 운영 호스트만 허용한다. master·volume·filer·admin·worker 관리 포트는 공유 namespace의
loopback에 제한하고 호스트에 게시하지 않는다. S3 내부 gRPC 19000은 bridge 안에서 수신하며,
호스트 게시와 외부의 직접 bridge 접근을 차단한다. 호스트 방화벽은 Docker 시작 전에 복구한다.

초기화가 승인된 전환에서는 새 데이터 디렉터리와 빈 버킷을 사용한다. 이전 MinIO의 객체·버전·KMS
상태를 복사하지 않는다. 모든 구버전 업로더를 중지한 뒤 이전 엔진·전용 데이터·불필요한 관리 포트·
임시 방화벽 예외를 제거한다. 애플리케이션 DB와 이벤트 저장소의 초기화 범위도 함께 확인한다.

## GitHub Environment와 Vault 권한

| Environment               | Variables                              | Secrets                                                 | 권한·용도                                    |
|---------------------------|----------------------------------------|---------------------------------------------------------|----------------------------------------------|
| `Prod-CD(<target>)`       | `VAULT_ADDR`, 필요 시 `VAULT_KV_MOUNT` | `VAULT_DEPLOY_READ_TOKEN`                               | 해당 배포 문서와 사용하는 참조 경로의 `read` |
| `Config-Update(<target>)` | 위와 동일                              | `VAULT_CONFIG_WRITE_TOKEN`, 변경 시 `VAULT_UPDATE_JSON` | 수정 대상의 `create`, `update`               |
| `Prod-CD(vault)` 복구     | 위와 동일                              | `VAULT_BOOTSTRAP_JSON`                                  | 외부 참조 없이 SSH와 Vault 기동값 공급       |

앱 속성을 수정할 Environment도 해당 서비스 target에 준비하고 공통 속성용은
`Config-Update(application)`을 사용한다. 모든 Environment의 배포 브랜치는 `main`으로 제한한다.
Actions runner가 Vault HTTPS에 접근할 수 있어야 한다. Config Server의 Vault 읽기 토큰에는
SSH 키가 있는 `deploy/*`를 허용하지 않는다.

토큰과 JSON 파일은 저장소 밖의 제한된 권한 파일로 준비한다. 다음 값을 실제 주소와 파일로 바꿔
각 target에 등록한다.

```bash
target=project
vault_addr=https://REPLACE_WITH_VAULT_HOST
gh variable set VAULT_ADDR --env "Prod-CD($target)" --body "$vault_addr"
gh variable set VAULT_ADDR --env "Config-Update($target)" --body "$vault_addr"
gh secret set VAULT_DEPLOY_READ_TOKEN --env "Prod-CD($target)" < /secure/project-read-token.txt
gh secret set VAULT_CONFIG_WRITE_TOKEN --env "Config-Update($target)" < /secure/project-write-token.txt
gh secret set VAULT_BOOTSTRAP_JSON --env 'Prod-CD(vault)' < /secure/vault-bootstrap.json
```

## 설정 변경과 재배포

운영 앱 속성·시크릿은 `secret/cowork-{service}/{local 또는 prod}`, Config 접속값·VM 주소·
프로파일은 `secret/deploy/{target}`의 `runtime`·`runtime_refs`에서 변경한다.
컨테이너 직접 주입값은 같은 배포 문서의 `application`·`application_refs`를 사용한다.
Config Server의 지원 프로파일·placeholder·우선순위는 [설정 규칙](../.claude/rules/config.md)을 따른다.
공통 `secret/application`은 기존 배포 참조용으로만 유지하며 Config 응답에 포함되지 않는다.
운영에서는 로컬 Vault seed를 실행하지 않는다.

[`cowork-prod-cd.yml`](../.github/workflows/cowork-prod-cd.yml)의 수동 작업을 사용한다.
`update-config`는 지정한 Vault 문서 전체를 교체하므로 보존할 속성도 포함한다.
`expected_version`에는 현재 버전을 넣고 새 경로 생성에만 `0`을 사용한다.

```bash
target=project
gh secret set VAULT_UPDATE_JSON --env "Config-Update($target)" < /secure/project.json
gh workflow run cowork-prod-cd.yml --ref main -f operation=update-config \
  -f target="$target" -f scope=deployment -f profile=base -f expected_version=3
```

앱 속성 변경은 `scope=application`, `target=<service>`, `profile=local|prod`를 사용한다.
변경 후 영향을 받는 앱을 같은 SHA로 재배포한다. native 기본값을 바꿨으면 새 Config 이미지가 필요하다.
모든 앱이 동적 refresh를 지원한다고 가정하지 않는다.

성공과 새 Vault 버전을 확인한 뒤, 이미지가 존재하는 `main`의 전체 SHA로 먼저 정적 검증한다.
`check_only` 성공은 실제 네트워크·migration·서비스 기동 성공을 뜻하지 않는다.

```bash
target=project
release_sha=REPLACE_WITH_40_CHARACTER_MAIN_SHA
gh workflow run cowork-prod-cd.yml --ref main -f operation=redeploy \
  -f service=project -f target="$target" -f sha="$release_sha" -f check_only=true
```

검증 성공 후 같은 입력의 `check_only=false`로 적용하고 readiness·Eureka 등록·projection을 확인한다.
완료 뒤 `gh secret delete VAULT_UPDATE_JSON --env "Config-Update($target)"`로 임시 입력을 삭제한다.
큰 문서는 Vault UI/API로 변경한다. 배포 snapshot은 SSH 전달 한도를 넘지 않도록 준비한다.

빈 데이터셋에서 앱 전체를 기동할 때는 `operation=redeploy`, `service=applications`,
`target=applications`를 함께 지정한다. 선택한 `main` 릴리스 SHA의 inventory에서 Gateway와
authorization·user·team·channel·project·roadmap·notification·preference·chat·voice의 11개 target을
가져온다. 각 서비스가 하나씩 있어야 하며, target 중복이나 서비스 스크립트 누락은 거부한다.
Config·Vault·Datastores·Storage·Monitoring·log-agent는 이 그룹에 포함하지 않는다. 필요한 인프라와
Config가 먼저 준비되어 있어야 한다.

```bash
gh workflow run cowork-prod-cd.yml --ref main -f operation=redeploy \
  -f service=applications -f target=applications -f sha="$release_sha" \
  -f configuration_version=0 -f vault_recovery=false -f check_only=true
```

그룹은 각 target의 최신 Vault 배포 문서를 사용하는 `configuration_version=0`과
`vault_recovery=false`만 허용한다. `check_only=true`는 11개 target 모두에 적용한다. 검증 성공 후
같은 입력에서 `check_only=false`로 바꾸어 기동한다. `applications`는 `update-config` 대상이나
단일 서비스와 섞어 사용할 수 없다. 단일 target의 버전 지정 재배포는 기존 입력을 사용한다.

Team↔Preference와 Project↔Channel은 상대 서비스의 snapshot을 기다릴 수 있으므로 앱 job을 함께
시작해야 한다. 수동 그룹과 자동 앱 배포 matrix에는 동시 실행 수 제한을 두지 않는다. 실행 환경에도
11개 앱 job이 기동할 수 있는 runner 여유를 확보한다. 각 job은 기존 target별 Environment, 배포 잠금,
readiness 검사와 runtime 성공 기록을 사용한다. 전체 workflow 실행 간 직렬화는 유지한다.

자동 배포는 target별 마지막 runtime 적용 성공 SHA를 기준으로 비교한다. 정적 검증·설정 변경의 성공은
이 기준을 바꾸지 않는다. 이미 적용한 SHA보다 이전 버전으로 복구할 때는 수동 재배포를 사용한다.
readiness 대기를 늘릴 때는 SSH `command_timeout`에 이미지 pull·실패 복구 시간도 확보한다.

`main` 이력을 재작성하면 기준 SHA가 현재 `main`의 조상이 아니게 되어 자동 배포가
`divergent deployment history`로 중단된다. 이때는 이미지가 존재하는 `main`의 전체 SHA로 target마다
수동 재배포한다. 성공하면 그 target의 기준 SHA가 새 이력으로 갱신된다.

### 자격 증명 교체

DB 계정과 그 계정을 포함한 DSN·URI를 함께 갱신한다. 배포 참조로 문자열 일부를 조합하지 않는다.
notification의 Vault DSN 키는 `db.dsn`, preference의 DB 계정 키는
`preference.db.username`·`preference.db.password`다. bootstrap 환경변수 이름으로 대체하지 않는다.

JWT는 authorization·Gateway·Chat, 서비스별 S3 key pair는 S3 서버와 해당 서비스,
LiveKit key pair는 LiveKit 서버·voice에서 교체 시점과 재배포 순서를 맞춘다.
Config/Eureka 계정·방화벽은 [접근 보호 절차](./config-server-access.md)를 따른다.

Firebase는 notification 프로파일의 `fcm.credentials-json`에 `service_account` JSON 전체를
문자열로 저장한다. `private_key` 줄바꿈을 JSON 직렬화로 보존하고 파일 경로나 중첩 객체로 전달하지 않는다.
다른 속성을 보존한 전체 문서를 저장소 밖에 준비하고 위 `update-config`에서 `target=notification`,
`scope=application`, `profile=local|prod`를 사용한다. 성공 후 notification을 재배포하고 임시 입력을 삭제한다.
빈 로컬 Vault의 최초 등록은 [로컬 실행](./local-run-guide.md)을 따른다.

### 외부 주소 변경

Eureka 광고 주소는 Gateway와 monitoring에서 도달 가능한 VM 주소·host port를 사용한다.
클라이언트용 S3·LiveKit 공개 주소는 실기기에서 접근을 확인한다.
`PUBLIC_WEB_ORIGINS`는 `secret/deploy/config`의 `runtime`에 쉼표 구분 문자열로 지정한다.
각 값은 경로·쿼리·후행 `/` 없는 `scheme://host[:port]`이며 첫 값은 OAuth `return_origin` 생략 시
복귀 주소다. Config → Gateway·Channel 순서로 재배포하고 프런트의 새 `return_origin` 사용은
Channel 배포가 끝난 뒤 시작한다.

## 로그 수집과 운영 기록

앱 VM마다 `deploy/log-agent-<vm>` 문서의 `runtime.LOG_HOST`·`LOKI_PUSH_URL`을 준비한다.
Docker data-root가 다르면 `DOCKER_CONTAINER_LOG_DIR`도 지정한다. 해당 target의 Environment를
등록하고 `service=log-agent`, `target=log-agent-<vm>`으로 수동 배포한다. 자동 배포도 필요하면 inventory에 등록한다.
Alloy로 전환할 때 기존 Promtail을 중지한다. 읽기 위치 형식이 달라 과거 로그가 재전송될 수 있다.
서비스별 실제 도착·필드 정규화는 [로그 TODO](./todo/items/43-monitoring/log-collection-contract.md)에서 확인한다.

운영 기록에는 target·VM 배치·실제 주소·적용 SHA·프로파일·Vault 및 참조 버전·readiness·관측 결과를
남긴다. RPO/RTO·백업·복구 훈련과 용량 목표는 실제 배치를 기준으로 별도 정한다.
토큰 교체와 릴리스 정리는 [인증 자동화](./todo/items/42-deployment/vault-auth-automation.md)·
[디스크 정리](./todo/items/44-deployment/release-retention.md)에서 추적한다.

## 실패 복구

앱 readiness 실패 시 이전 컨테이너를 재시작하지만 DB migration과 외부 부작용은 되돌리지 않는다.
이전 이미지와 호환되는 스키마를 유지하고, 부분 적용된 migration은 실제 스키마부터 복구한다.
user의 실패 migration 이력만 지워 기동을 강행하지 않는다. 최초 설치는 이전 컨테이너가 없으며,
monitoring·Vault·Alloy에는 일반 앱의 자동 복구 경로가 적용되지 않는다.

`*-candidate`·`*-previous`가 남으면 Docker 상태·포트·이미지를 확인해 복원하거나 정리한다.
복원된 컨테이너의 주소·포트가 새 설정과 다르면 원래 경로로 상태를 확인한다.
수동 롤백은 이전 `sha`와 `configuration_version`을 지정한다. 이 값은 배포 문서 버전만 고정하므로
`runtime_refs`·`application_refs`와 Config 앱 속성도 필요하면 먼저 복원한다.
실행·복구용 컨테이너의 bind mount가 참조하는 릴리스·snapshot은 삭제하지 않는다.

Vault 중단·봉인 복구는 `service=vault`, `target=vault`, `vault_recovery=true`와 외부 보관한
`VAULT_BOOTSTRAP_JSON`을 사용한다. unseal 자료도 Vault 밖에 보관한다.
상태 토픽을 바꾸거나 기존 projection을 복구할 때는 [전환 절차](./kafka-state-topic-cutover.md)를 따른다.

### VM 재부팅 후 복구

운영 Vault는 file storage와 단일 unseal key로 동작하므로 VM이 재부팅되면 sealed 상태로 시작한다.
이 동안 Config Server는 모든 설정 요청에 `500`을 반환하고, 기동 시 Config를 읽는 앱은 재시작을 반복한다.
자동 unseal은 구성되어 있지 않으므로 운영자가 다음 순서로 복구한다.

1. Vault를 unseal한다. `Prod-CD(vault)`의 `VAULT_BOOTSTRAP_JSON`에는 `ssh`와 `runtime`의
   `VAULT_EXTERNAL_HOST`·`VAULT_BIND_IP`·`VAULT_DATA_VOLUME`·`VAULT_UNSEAL_KEY`가 있어야 한다.
   `sha`는 vault target에 마지막으로 적용한 SHA를 사용한다. 워크플로는 unseal 뒤 `vault status`와
   컨테이너 healthcheck가 모두 정상이어야 성공한다. 단, unseal 뒤 `docker compose up --wait`를 실행하던
   이전 SHA의 `vault.sh`는 unseal에 성공해도 실패로 끝난다. 이때는 워크플로 결과 대신 2번의 Config `200`
   확인으로 복구 여부를 판단한다.

   ```bash
   release_sha=$(gh api 'repos/team-cowork/cowork-server/deployments?environment=Prod-CD(vault)&task=cowork-runtime' --jq '.[0].sha')
   gh workflow run cowork-prod-cd.yml --ref main -f operation=redeploy \
     -f service=vault -f target=vault -f sha="$release_sha" -f check_only=false -f vault_recovery=true
   ```

2. `CONFIG_ALLOWED_CIDRS`에 포함된 VM에서 user 서비스 계정으로 설정 조회가 `200`인지 확인한다.
   응답 본문에는 시크릿이 있으므로 상태 코드만 출력하고, 비밀번호는 curl 프롬프트에 입력한다.

   ```bash
   curl -sS -o /dev/null -w '%{http_code}\n' -u '<cowork-user 계정>' 'http://<Config VM 사설 IPv4>:8761/cowork-user/prod'
   ```

   Config는 Vault 복구 뒤 다음 설정 요청에서 AppRole로 다시 로그인한다. 계속 `500`이면 Config 로그에서
   Vault 연결·로그인 실패를 확인하고 `service=config`를 마지막 적용 SHA로 재배포한다.
3. 앱 컨테이너는 `unless-stopped` 정책으로 다시 기동한다. 각 VM의 `docker ps`에 `Restarting`인 앱이
   없는지 확인하고, 같은 계정으로 Config·monitoring을 제외한 앱이 Eureka에 `UP`으로 등록됐는지 확인한다.

   ```bash
   curl -sS -u '<cowork-user 계정>' -H 'Accept: application/xml' 'http://<Config VM 사설 IPv4>:8761/eureka/apps' \
     | grep -E '<name>|<status>'
   ```

   계속 실패하는 앱은 Config가 `200`을 반환한 뒤 위 재배포 절차로 마지막 적용 SHA를 다시 배포한다.

### DataGSM 웹훅 미반영

응답 연결이 끊기거나 `503`이면 같은 ID·내용·발생 시각으로 재전달한다.
`409 event_id_conflict`는 발신자 기록과 대조하고 최초 inbox를 덮어쓰지 않는다.
성공 응답 후에도 미반영이면 DB·Kafka relay 적체와 user의 quarantine·업무 제약을 확인한다.
공유 DB backlog gauge는 replica별 합산을 피하고 최신 성공 관측값 또는 `max`를 사용한다.
관측 실패 뒤 남은 이전 값을 최신 정상값으로 해석하지 않는다.

outbox 삭제나 timestamp 변경으로 적체를 우회하지 않는다. 미발행 outbox가 참조하는 만료 inbox는
보존하며 접수 기한 이후에도 발행을 기다린다. Kafka 데이터 유실은 inbox만으로 복구할 수 없다.
보관이 끝난 ID를 새 발생 시각으로 재사용하지 않는다. inbox migration 전에는 구버전 replica를 중지하고
이전 기록의 backfill 없이 기존 재전달 데이터를 처리할 방침을 정한다.

## Backend VM의 HTTP 접근 보호

업무 backend는 전용 VM의 사설 IPv4에 HTTP 포트를 게시한다. 운영자는 첫 앱 배포 전에
`deploy/prod/bootstrap-backend-firewall.sh`로 고정 ingress 정책을 설치한다. 대상은 Authorization,
User, Channel, Project, Team, Notification, Chat, Roadmap, Voice, Preference다. Config는 별도
Config 방화벽을 사용한다. Gateway, DB, 스토리지, LiveKit의 포트에 이 정책을 적용하지 않는다.

정책 파일은 `/etc/cowork/backend-firewall/policy.json`이며 소유자는 `root:root`, 권한은 `0600`이다.
부모 디렉터리는 `0700`으로 제한한다. 각 VM의 실제 게시 주소와 포트를 기록한다. 예시는 다음과 같다.

```json
{
  "bindings": [
    {"address": "10.0.0.140", "host_port": 8081, "container_port": 8081}
  ],
  "peers": ["10.0.0.127/32", "10.0.0.137/32"]
}
```

`peers`에는 Gateway와 기존 monitoring VM을 포함한다. 이 정책은 monitoring 배포나 설정을 바꾸지
않는다. 다른 업무 서비스는 허용하지 않는다. Voice에 DB VM을 추가하려면 먼저 LiveKit의 webhook이
Voice에 직접 연결되는지 확인한다. Gateway 경유 webhook이면 Voice에 DB peer를 추가하지 않는다.
주소와 peer는 명시적 RFC1918 IPv4만 허용한다. 서비스별 기본 포트는 다음과 같다. Vault 문서의
`HOST_PORT` override가 있으면 실제 값을 사용한다.

| 서비스 | VM 게시 포트 | 컨테이너 포트 |
|--------|-------------:|--------------:|
| Authorization | 8081 | 8081 |
| User | 8082 | 8082 |
| Channel | 8083 | 8083 |
| Project | 8089 | 8084 |
| Team | 8085 | 8085 |
| Notification | 8086 | 8086 |
| Chat | 8087 | 8087 |
| Roadmap | 8088 | 8088 |
| Voice | 8089 | 8089 |
| Preference | 9001 | 9001 |

helper는 `COWORK_BACKEND` 체인과 해당 체인으로 향하는 `INPUT`·`DOCKER-USER` jump만 교체한다.
`iptables-restore --noflush`의 한 filter-table commit으로 적용하며 다른 체인·규칙을 지우지 않는다.
Docker 게시 경로는 conntrack의 원래 목적지 주소·게시 포트로 제한한다. 호스트가 직접 수신하는
같은 주소·포트는 `INPUT`에서 제한한다. 호스트 자체 health 확인을 위한 loopback 경로는 유지한다.

컨테이너 IP 직결 경로도 보호하기 위해 `docker+`·`br+` 인터페이스로 향하는 해당 컨테이너 포트를
제한한다. 전용 VM에서 그 포트를 쓰는 모든 Docker 컨테이너에 같은 제한이 적용된다. 다른 포트,
외부로 나가는 요청, conntrack 응답 방향에는 적용하지 않는다. SSH·DB 등 다른 포트의 규칙은
교체하지 않는다. 이 helper는 표준 Docker bridge와 IPv4 iptables를 전제로 한다. 적용 전에 Docker
network의 `EnableIPv6=false`와 컨테이너 global IPv6 주소 부재를 확인한다. 별도 이름의 bridge,
host/macvlan/ipvlan 네트워크, IPv6 컨테이너 ingress에는 이 정책을 그대로 적용하지 않는다.

### 초기 설치와 정책 변경

1. 검토한 같은 커밋에서 `bootstrap-backend-firewall.sh`, `backend-firewall.py`,
   `backend-firewall.service`를 준비한다. VM별 `policy.json`도 검토한다.
2. 파일별 SHA-256과 커밋 ID를 기록한다. 진행 중인 앱 배포가 없는지 확인한다.
3. 관리 권한으로 VM의 `/root/cowork-backend-firewall-bootstrap`을 `root:root`, `0700`으로 만든다.
   네 파일을 이 디렉터리에 `root:root`, `0600`으로 복사한다.
4. 복사한 **root 소유 파일**의 SHA-256이 검토한 값과 일치하는지 확인한다. 릴리스 캐시나 전송용
   사용자 디렉터리에서 설치 스크립트를 직접 실행하지 않는다.
5. 다음 명령으로 설치한다.

```sh
sudo /usr/bin/bash /root/cowork-backend-firewall-bootstrap/bootstrap-backend-firewall.sh
```

설치 스크립트는 소스와 부모 디렉터리의 root 소유권·쓰기 권한을 확인한 뒤 정책을 검증한다.
helper·unit·정책을 고정 경로에 원자적으로 복사하고 현재 규칙을 적용한다. 이어서
`cowork-backend-firewall.service`를 Docker의 필수 선행 unit으로 활성화한다. Docker를 재시작하지
않는다. 다음 VM 부팅과 Docker 재시작에서는 방화벽 복원이 성공해야 Docker가 시작된다. 복원 unit을
수동으로 중지하면 Docker도 중지될 수 있으므로 정상 정책 변경에 `systemctl stop`을 사용하지 않는다.

일반 앱 CD는 이 고정 정책을 수정하지 않는다. `check_only`와 실제 배포 모두 설치된 root 소유 helper의
`check-binding`을 호출하여 주소·게시 포트·컨테이너 포트의 정확한 일치와 복원 unit의 enabled·active
상태를 확인한다. 이 명령은 설치된 정책으로 현재 규칙을 다시 적용하지만 정책 파일·peer·포트를
변경하지 않는다. 따라서 `check_only`도 기존 보호 규칙을 복원할 수 있다. helper가 없거나 바인딩이
일치하지 않거나 규칙 복원이 실패하면 배포를 중단한다. Config·Gateway·인프라 배포에는 이 검사를
적용하지 않는다.

`BIND_IP`, `HOST_PORT`, `CONTAINER_PORT`, peer 목록, Docker network 조건을 바꿀 때는 앱 컨테이너를
중지하고 새 정책을 검토·재설치한 다음 배포한다. Vault 값만 변경하지 않는다. 정책 설치가 실패하면
앱을 시작하지 않는다. 허용 peer의 health 응답과 비허용 peer의 연결 차단을 운영 환경에서 확인하고
helper·unit·정책 해시 및 확인 결과를 설치 기록에 남긴다.

## 운영 스토리지 GitHub CD

`storage`는 `prod-cd`의 독립 인프라 target이다. 자동 배포 순서는 Vault → Datastores → Storage → Config →
나머지 서비스다. 선택된 Storage 배포가 실패하면 뒤의 Config·앱 배포는 실행하지 않는다. 수동 단일 대상 배포는
선택한 target만 실행한다. `deploy/prod/storage/` 또는 `deploy/config/seaweedfs/`의 변경은 Storage
배포 비교에 포함한다. SeaweedFS와 AWS CLI 이미지는 선택한 GitHub SHA의 Compose에 고정된 digest를
사용하며 별도 GHCR 빌드나 토큰이 필요 없다.

처음 한 번의 VM 준비는 관리자가 수행한다. SeaweedFS 데이터 디렉터리, root 소유 방화벽 helper·정책·
systemd unit, `cowork-storage` SSH 계정을 준비한다. 이 계정의 UID와 기본 GID는 `10001`, home은
`/home/cowork-storage`다. home은 `0700`으로 제한한다. `/opt/cowork`와 `/opt/cowork/storage`는
`root:10001`, `0710`으로 설정하여 지정 계정의 경로 통과만 허용한다. 기존 `data`, `admin-data`,
`lifecycle-data` 디렉터리는 `10001:10001` 소유로 유지한다. 계정은 Docker 사용 권한과 다음 고정
방화벽 명령의 sudo 권한이 필요하다.

```text
/usr/bin/python3 -I /usr/local/libexec/cowork-storage-firewall.py check-binding
```

Docker 사용 권한은 root 상당 권한이다. 이 계정 분리는 VM에 대한 보안 격리를 보장하지 않는다.
일반 CD에서는 릴리스 캐시의 Python이나 초기 설치 `prepare`·`apply`를 sudo로 실행하지 않는다.
VM·DB·객체 초기화, 이전 엔진·볼륨 삭제는 CD에 포함하지 않는다.

Vault `secret/deploy/storage`에는 해당 계정의 `ssh` 정보와 `runtime.STORAGE_SETTINGS_JSON`만 저장한다.
이 키의 값은 [운영 스토리지 초기 설정](../deploy/prod/storage/README.md)의 여섯 필드
`bind_ip`, `allowed_cidrs`, `bucket`, `region`, `public_web_origins`, `identities`를 가진 **JSON 문자열**이다.
`identities`에는 admin·Team·User·Chat의 별도 키가 들어간다. 앱에서 사용하는 세 서비스 키와 일치시킨다.
관리자 키는 앱 Config 경로에 기록하지 않는다. `application`·`application_refs`는 비우며
`APP_CONFIG_PROFILE`은 필요하지 않다. 다른 target에서 `STORAGE_SETTINGS_JSON`을 전달하면 거부한다.
같은 키의 `runtime_refs`도 지원하지만 현재 운영은 배포 문서에 직접 저장하여 `update-config`로 관리한다.

GitHub에는 `Prod-CD(storage)`와 `Config-Update(storage)` 환경을 만든다. 배포 읽기 토큰은
`secret/data/deploy/storage`만 읽고, 설정 쓰기 토큰은 같은 문서의 CAS 갱신만 허용한다. 참조 경로를
도입할 때만 읽기 토큰에 그 경로의 `read`를 추가한다. Config AppRole과 일반 앱 배포 토큰에는
Storage 배포 문서나 관리자 키 읽기 권한을 주지 않는다.

`services/storage.sh`는 `cowork-storage` 계정으로 `storage/runtime.py`를 실행한다. 선택한 SHA의
IAM 렌더러·버킷 설정 스크립트·Compose와 Vault 문서 버전을 사용한다. IAM·관리자 환경 파일은 계정의
private snapshot에 생성한다. 컨테이너와 계정의 UID가 같으므로 비밀 파일을 공개 읽기로 바꿀 필요가
없다. 데이터 mount 세 개만 기존 `/opt/cowork/storage` 아래의 고정 경로로 연결한다. 경로가 없으면
배포를 중단하며 새 데이터 디렉터리를 자동 생성하지 않는다.

`check_only`는 비밀 snapshot과 Compose 구문을 검증하고, 고정 root helper로 `bind_ip`·허용 peer
집합과 설치 정책의 정확한 일치를 확인한다. helper는 기존 보호 규칙을 복원할 수 있다. 컨테이너를
기동하거나 버킷 설정을 변경하지 않는다. 실제 배포는 이미지 pull 뒤 server를 교체하고 버킷 정책·
CORS·만료 설정을 적용한 다음 admin·worker를 새 server namespace에 연결한다. server와 worker가
healthy이고 admin이 running이어야 런타임 배포 성공을 기록한다.

단일 데이터 디렉터리에 후보와 이전 server를 동시에 기동하지 않는다. 이미지 변경 뒤에는 데이터 형식이
변했을 수 있으므로 실패 시 이전 이미지로 자동 복귀하지 않는다. 단계가 실패하면 CD를 실패로 종료하고
데이터와 실제 배포 snapshot을 유지한다. 운영자는 실패 단계·private 로그·실행 상태를 확인한 다음
검토한 SHA와 Vault 버전으로 다시 배포한다. 이 동작은 기존 객체를 초기화하는 기능이 아니다.

## 운영 데이터 저장소 GitHub CD

`datastores`는 MySQL·PostgreSQL·MongoDB·Redis·Kafka·Elasticsearch를 관리하는 독립 인프라 target이다.
자동 배포 순서는 Vault → Datastores → Storage → Config → 나머지 서비스다. 선택된 Datastores 배포가
실패하면 뒤의 배포는 실행하지 않는다. 수동 단일 대상 배포는 선택한 target만 실행한다. 배포 비교에는
`deploy/prod/datastores/`, `deploy/images/elasticsearch/`, `deploy/compose/stack.yaml`의 Kafka topic 정의를
포함한다. 공용 이미지를 사용하므로 GHCR 인증 토큰은
전달하지 않는다. Elasticsearch는 선택한 소스 SHA의 Dockerfile로 필요한 플러그인 이미지를 구성한다.

처음 한 번의 VM 준비는 관리자가 수행한다. 데이터 볼륨, Docker network, root 소유 방화벽과
`/etc/cowork/datastores-bootstrap.json`을 준비한다. manifest는 등록한 일곱 데이터 볼륨의 생성 시각,
network ID, Kafka cluster ID를 기록한다. `cowork-datastores` SSH 계정은 UID·기본 GID `10002`와
Docker 사용 권한을 사용한다. home과 설정 snapshot을 다른 사용자에게 공개하지 않는다. 일반 CD에서
릴리스 캐시의 코드를 sudo로 실행하거나 신규 VM·볼륨을 자동 초기화하지 않는다. 데이터 삭제, DB reset,
이전 엔진 정리도 일반 CD에 포함하지 않는다.

Vault `secret/deploy/datastores`에는 해당 계정의 `ssh` 정보와 `runtime.DATASTORE_SETTINGS_JSON`만
저장한다. 값은 `bind_ip`, `mysql`, `postgres`, `mongodb`, `kafka`를 가진 **JSON 문자열**이다.
`mysql`에는 `root_password`, `user`, `password`가 필요하다. `postgres`와 `mongodb`에는 각각
`user`, `password`, `kafka`에는 기존 `cluster_id`가 필요하다. 구체적인 필드와 값은 Datastores
runtime이 검증한다. 허용 peer 정책은 root 소유 방화벽에서 관리한다. 계정·비밀번호·cluster ID는
실제 저장소와 일치시킨다. 초기 기동용 환경 변수만 바꾸어 기존 DB 비밀번호를 회전시키지 않는다.

`application`·`application_refs`는 비우고 `APP_CONFIG_PROFILE`은 지정하지 않는다.
`DATASTORE_SETTINGS_JSON`과 `STORAGE_SETTINGS_JSON`은 각각 자기 target에만 전달할 수 있다.
Config Server application scope와 일반 앱 환경 변수에 두 설정 묶음을 저장하거나 전달하면 거부한다.
운영 문서는 JSON 문자열을 직접 저장하여 `update-config`로 관리한다. `runtime_refs`를 사용하는 경우에도
같은 target 격리 규칙을 적용한다.

GitHub 환경은 `Prod-CD(datastores)`와 `Config-Update(datastores)`를 사용한다. 배포 읽기 토큰은
`secret/data/deploy/datastores`만 읽고, 쓰기 토큰은 같은 문서의 CAS 갱신만 허용한다. Config AppRole과
일반 앱 배포 토큰에는 이 문서의 읽기 권한을 부여하지 않는다.

`services/datastores.sh`는 전용 계정으로 Datastores runtime을 실행한다. 설정 snapshot은
`/home/cowork-datastores/.local/state/cowork/settings/datastores` 아래에 생성한다. `check_only`는
설정·Compose·등록된 기존 자원·고정 방화벽 바인딩을 검증하며 저장소를 기동하거나 초기화하지 않는다.
실제 배포는 선택한 소스 SHA와 Vault 문서 버전으로 기존 데이터 볼륨을 사용하는 엔진을 갱신한다.
필수 단계 또는 준비 상태 확인이 실패하면 런타임 배포 성공을 기록하지 않는다. 데이터와 실제 배포
snapshot은 유지하며, 데이터 형식이 변경될 수 있으므로 이전 이미지로 자동 복귀하지 않는다.
