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
Docker 네트워크·볼륨은 VM 사이에 공유되지 않는다. LiveKit 미디어·S3 요청은 별도 공개 경로가 필요하다.
실제 S3 접근 정책은 [TODO](./todo/items/13-storage/object-storage-public-access-contract.md)에서 관리한다.

같은 VM에 같은 서비스의 여러 target을 배치하는 구성은 지원하지 않는다. 배치·실제 포트는 Vault와
대조한다. 필요한 외부 볼륨은 배포 전에 생성한다. Vault의 TLS 프록시만 사설 listener에 접근시키고,
현재 단일 unseal key 입력과 다른 다중 share 구성은 별도 복구 절차를 준비한다.

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

자동 배포는 target별 마지막 runtime 적용 성공 SHA를 기준으로 비교한다. 정적 검증·설정 변경의 성공은
이 기준을 바꾸지 않는다. 이미 적용한 SHA보다 이전 버전으로 복구할 때는 수동 재배포를 사용한다.
SSH `command_timeout`은 45분이며 `HEALTH_TIMEOUT_SECONDS`는 1~1050초로 제한한다.
후보·롤백 대기 두 번과 준비 시간 600초를 고려한 상한이다. 이미지 pull 자체에 시간 제한이 있는 것은 아니다.
실패 후보 로그 보존과 기존 비정상 컨테이너의 롤백 대기 생략은 [#450](https://github.com/team-cowork/cowork-server/pull/450),
health 상한 검증은 [#459](https://github.com/team-cowork/cowork-server/pull/459)에 반영되어 있다.

### 수동 복구 후 정리

CD로 전환한 환경에서는 실행 중인 이미지 SHA와 `cowork-runtime` 성공 기록을 대조한다.
사용하지 않는 `*-old`·수동 `:local`·`verify-test` 컨테이너와 임시 Docker 네트워크 연결은
실행·복구 의존성이 없는지 확인한 뒤 정리한다. 임시 `~/pref.env`·`~/auth.env`·`~/noti.env`·
`~/user.env`와 작업용 Firebase 키 파일도 실제 사용 여부를 확인해 불필요한 사본과 참조를 제거한다.
Firebase 자격 증명은 Config 공급 경로, 클라이언트와의 프로젝트 일치, 실제 수신을 확인한다.

### 자격 증명 교체

DB 계정과 그 계정을 포함한 DSN·URI를 함께 갱신한다. 배포 참조로 문자열 일부를 조합하지 않는다.
notification의 Vault DSN 키는 `db.dsn`, preference의 DB 계정 키는
`preference.db.username`·`preference.db.password`다. bootstrap 환경변수 이름으로 대체하지 않는다.

JWT는 authorization·Gateway·Chat, S3 key pair는 S3 서버·chat·team·user,
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

### 배포 성공 기록 대조

2026-10-08 점검에서 조회한 `task=cowork-runtime`의 target별 마지막 성공 기록이다. 자동 생성된 Environment job 성공과
`check_only`는 제외했다. 시각은 한국 표준시다. 이 기록은 현재 가용성이나 미배포를 확정하지 않는다.

| target | 마지막 성공 SHA | 성공 기록 시각 (KST) | 실행 기록 |
|---|---|---|---|
| authorization | `231bfea6` | 2026-10-04 01:25 | [run](https://github.com/team-cowork/cowork-server/actions/runs/37136725666) |
| channel | `231bfea6` | 2026-10-05 18:36 | [run](https://github.com/team-cowork/cowork-server/actions/runs/37291017089) |
| chat | `231bfea6` | 2026-10-06 01:01 | [run](https://github.com/team-cowork/cowork-server/actions/runs/37337456074) |
| config | `231bfea6` | 2026-09-29 00:52 | [run](https://github.com/team-cowork/cowork-server/actions/runs/36446755559) |
| gateway | `231bfea6` | 2026-09-29 00:56 | [run](https://github.com/team-cowork/cowork-server/actions/runs/36447254521) |
| notification | `231bfea6` | 2026-10-06 19:51 | [run](https://github.com/team-cowork/cowork-server/actions/runs/37452069393) |
| preference | `231bfea6` | 2026-10-04 03:28 | [run](https://github.com/team-cowork/cowork-server/actions/runs/37143971044) |
| project | `231bfea6` | 2026-10-06 00:35 | [run](https://github.com/team-cowork/cowork-server/actions/runs/37333799641) |
| roadmap | `231bfea6` | 2026-10-04 01:01 | [run](https://github.com/team-cowork/cowork-server/actions/runs/37135216263) |
| team | `231bfea6` | 2026-10-03 23:47 | [run](https://github.com/team-cowork/cowork-server/actions/runs/37130756211) |
| vault | `08706cdd` | 2026-10-07 13:47 | [run](https://github.com/team-cowork/cowork-server/actions/runs/37571972300) |
| voice | `231bfea6` | 2026-10-05 23:52 | [run](https://github.com/team-cowork/cowork-server/actions/runs/37326134539) |
| user | 성공 기록 없음 | — | 환경 job 실패 기록만 확인 |
| monitoring | 성공 기록 없음 | — | 환경 job 실패 기록만 확인 |

당시 [자동 CD](https://github.com/team-cowork/cowork-server/actions/runs/37571972300)는 Vault 성공,
Config 실패, 앱 배포 skipped였다. [notification 배포](https://github.com/team-cowork/cowork-server/actions/runs/37452069393)의
성공 SHA `231bfea6`도 `fcm.credentials-json`과 `fcm.NewSender` 초기화가 필수이므로,
과거 자격 증명 부재에 따른 기동 장애는 해당 배포에서 해소된 것으로 판단했다.
실제 Firebase 전송 권한·키 파일 폐기 여부는 이 성공 기록만으로 판단하지 않는다.

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

### 채팅 메시지 범위와 검색 색인 점검

메시지의 `teamId`·`projectId`는 채널 projection에서 결정하며 요청에서 받지 않는다.
부모 메시지는 같은 채널에 존재해야 한다. 답장의 답장을 허용하고 답장도 unread에 포함한다.
`parentMessageId` 조회는 직계 답장을 반환하며, 그 응답의 `mentionedMessage`는 채우지 않는다.
이 계약과 읽기 전용 감사 도구는 [#412](https://github.com/team-cowork/cowork-server/pull/412)·
[#448](https://github.com/team-cowork/cowork-server/pull/448)에 반영되어 있다.

기존 데이터를 유지·정정하기로 한 환경에서는 다음 절차를 사용한다. 데이터 유지·복구·이관 여부는
운영 담당자가 결정하며 배포의 필수 조건이 아니다.

1. 채널 범위 확정과 읽기 경로의 부모 채널 제한이 적용된 버전인지 확인한다.
2. `cowork-chat`에서 `MONGODB_URI`를 설정하고 `npm run ops:message-scope-audit`를 실행한다.
   JSON Lines 보고서와 마지막 범주별 건수를 저장소 밖의 접근 제한된 위치에 보관한다.
3. `CHANNEL_MISSING_OR_DELETED`·`CHANNEL_SCOPE_INVALID`는 채널 삭제·projection 복구 상태를
   먼저 확인하며 자동 정리 대상에 넣지 않는다.
4. `MESSAGE_SCOPE_MISMATCH`는 활성 채널의 `teamId`·`projectId` 정정을,
   `PARENT_INVALID_ID`·`PARENT_MISSING`·`PARENT_CROSS_CHANNEL`은 부모 참조의 `null` 해제를 검토한다.
   메시지 ID와 이전·새 값을 포함한 적용 목록을 승인받는다.
5. 승인한 목록만 `_id`·`channelId`·감사 당시 필드 값으로 조건부 갱신한다.
   조건이 바뀐 문서는 건너뛰고 다시 감사한다. 원본과 적용 결과를 복구 가능한 운영 기록으로 보관한다.
6. 정정된 메시지가 색인 대상이면 `npm run ops:message-search-index -- rebuild`를 실행한다.
   완료 뒤 감사 명령과 색인 `status`를 다시 확인한다.

감사는 읽기 전용이다. 실제 데이터 변경과 재색인은 보고서 검토·승인 후 별도 운영 작업으로 수행한다.

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
