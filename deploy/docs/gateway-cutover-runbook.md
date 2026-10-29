# Gateway 고정 포트 이관과 배포 상태 복구

## 최초 전환

현재 운영 Gateway Nomad 할당이 호스트 8080을 사용한다. 첫 #662 자동 CD는 기존 할당을 종료하고 새 Gateway를 고정 포트 **21000**에 시작한다. 이전 8080 할당의 종료와 포트 해제를 확인한 뒤, 같은 VM의 NGINX가 8080을 인수한다. 첫 전환은 Gateway VM의 현재 4 GB 용량에서도 실행되며 연결 중단이 발생할 수 있다. 호스트 준비 단계는 NGINX를 설치하되 검증된 대상이 생길 때까지 비활성 상태로 둔다.

Gateway와 Authorization 소비자의 변경은 첫 전환에 필수다. 다른 업무 앱이 같은 릴리스에서 변경되면 모두 서비스별 배포 플래그를 적용한다. 현재 배포 manifest가 가리키는 소스 이후 공통 빌드 입력도 바뀌어, 첫 릴리스에는 여러 앱이 포함될 수 있다. CD는 Gateway를 먼저 전환하고, 그 뒤 변경된 업무 앱을 배포한다.

두 서비스는 하나의 저장소 수준 GitHub `DATAGSM_WEBHOOK_SECRET`을 사용해야 한다. CD는 각 서비스의 고정된 Vault 버전에서 값을 읽어 같음을 확인한 뒤 작업을 시작한다. 공유 Redis의 2 GB VM, `maxmemory=256mb`, Nomad 512 MiB 상한은 그대로 둔다. 첫 전환은 Stream이 비어 있고 Redis 사용량이 256 MiB 한도의 75% 이하일 때만 시작한다. 현재 Authorization 수신 메트릭에는 최근 24시간과 7일의 양의 유입량을 계산할 시계열이 없어, 대기열이 버틸 시간을 보장할 수 없다.

공유 Redis에는 키별 접근 제한이 없다. Redis에 연결된 다른 서비스도 웹훅 원문을 읽거나 배포 상태·Stream 키를 수정할 수 있으므로, Redis 접속 서비스 전체를 신뢰한다는 운영 전제 아래 사용한다.

NGINX가 새 Gateway의 외부 요청을 받을 가능성이 생기기 전의 실패는 이전 고정 포트 Gateway로 되돌릴 수 있다. NGINX 시작 직전에는 영속 접수한 웹훅이 있을 수 있다고 표시한다. 이 경계를 지난 실패에는 새 Gateway와 Redis Stream 항목을 보존하고, 변경된 업무 서비스의 `recovering` 플래그를 유지한다. Authorization 소비자를 CD로 정상 배포하고 적체를 비운 뒤 복구한다. 이미 200을 반환한 Stream 항목을 삭제하거나, 소비자 없는 구 Gateway로 자동 복귀하지 않는다.

## 이후 릴리스

Gateway는 21000과 21001 중 하나를 호스트 고정 포트로 사용한다. 다음 Gateway 교체는 현재 포트와 반대 포트에 canary를 올리고, 새 할당의 Nomad/Consul 준비·Redis 상태 동기화, Gateway→11개 업무 upstream의 `/api/health` 응답과 최소 정상 시간을 확인한 뒤 NGINX를 새 대상으로 전환한다. 이전 프록시 worker의 연결이 최대 300초 동안 빠지기를 기다린 다음 Nomad를 promote한다. 고정 포트이므로 동일 Gateway 할당의 재스케줄 때 NGINX 대상 포트가 바뀌지 않는다. 두 할당이 겹치는 이후 Gateway 교체에는 **8 GB VM**이 필요하다. 첫 전환 뒤 canary 설정 0→1만 바꾸는 단계는 기존 할당을 재사용하며, CD가 같은 할당 ID와 활성 프록시 대상을 확인한다.

CD는 OpenTofu 저장 계획에서 실제 변경되는 업무 서비스만 고른다. 각 서비스에 실행 ID와 `deploying` 단계를 Redis에 쓰고 같은 연결의 `WAITAOF`로 AOF 반영을 확인한다. Gateway의 로컬 상태 조회에 반영되고 해당 서비스의 진행 중인 일반 HTTP 요청 수가 0이 된 뒤에만 업무 앱 변경을 적용한다. WebSocket과 SSE는 이 요청 수에서 제외되며 Gateway 전환 후 재연결될 수 있다.

변경된 **모든** 업무 앱의 Nomad/Consul 준비가 확인되면 Gateway의 `/api/health`에서 해당 서비스가 각각 `UP`인지 확인한다. 이 검사는 Gateway가 자신의 upstream readiness 경로에 연결할 수 있음을 확인한다. Authorization은 Redis Stream 연결과 항목·pending 0건도 확인한다. 이 조건이 모두 만족된 뒤 서비스별 플래그를 지운다. 실패하면 플래그를 `recovering`으로 유지하고, 일반 릴리스에서는 이전 작업을 복원한 뒤 Gateway upstream도 다시 확인한다. 첫 전환 실패에는 새 Gateway를 유지하고 변경된 앱을 다음 CD 실행에서 전진 복구한다.

Gateway만 바뀌는 릴리스도 기존 프록시 파일이 실제 활성 할당 포트를 가리키고 8080 응답이 NGINX에서 왔는지 확인한다. 후속 Gateway 롤백에서 구 할당이 살아 있으면 먼저 NGINX를 구 포트로 돌린다. 이미 종료됐다면 현재 할당을 유지하면서 구 버전을 canary로 준비하고 검증한 뒤 돌린다. 최종 검증이 끝나야 GitHub 배포 성공 기록과 Release 게시가 진행된다.

## 중단된 실행 복구

보호된 배포 runner는 `deploy.lock`으로 동시 실행을 막는다. 이전 실행이 강제 종료되어 `deploying`이 남았다면 다음 CD 실행은 저장된 runId로 해당 항목을 `recovering`으로 바꾸고 AOF 확인 후 진행한다. 변경된 서비스는 새 실행 ID로 인수하며, 계획에서 빠진 서비스는 현재 Nomad/Consul 준비와 Gateway upstream 응답을 확인한 뒤 해제한다. Authorization은 대기열 0건도 요구한다. 첫 전환 중 중단됐고 새 Gateway가 이미 활성이라면, 다음 CD 실행은 21000 포트와 NGINX 경로를 확인·복구한 뒤 Authorization 소비자 배포를 재시도한다.

서비스가 준비됐지만 플래그만 남았으면 보호된 runner에서 다음 명령으로 개별 복구한다.

```bash
flock "$COWORK_PLATFORM_STATE_DIR/deploy.lock" bash deploy/production/apply.sh deploy/production/releases.json --recover-state cowork-authorization
```

같은 명령에서 마지막 서비스 이름을 다른 `cowork-<service>`로 바꿀 수 있다. 준비·upstream 확인 또는 Authorization 대기열 비우기가 실패하면 플래그를 지우지 않는다. Auth 소비자가 준비되지 않았다면 앱 코드를 수동 배포하지 말고 CD로 수정 릴리스를 진행한다.

후속 canary 단계에서 runner가 강제 종료돼 Gateway 실행 할당이 둘 남으면 자동 CD를 재시도하지 않는다. Nomad에서 두 할당의 JobVersion·호스트 포트·Consul health, `/internal/deployment-state` 동기화와 `/api/health` upstream 상태를 확인하고, NGINX의 `/etc/cowork/gateway-upstream.conf`가 가리키는 포트를 대조한다. NGINX가 건강한 **새 후보**를 가리키면 해당 deployment를 promote하고 구 할당 종료를 확인한다. NGINX가 건강한 **구 할당**을 가리키고 새 후보도 건강하면 `gateway-switch.yml`로 후보 포트를 선택·검증한 뒤 promote한다. 후보가 비정상이면 구 할당을 계속 서비스하며 실패한 배포를 정리하고 CD에서 수정 릴리스를 진행한다. 이후 `gateway-verify.yml`로 8080 응답과 target 일치를 확인한다. 판단이 불가능하면 현재 건강한 대상을 유지하고 프록시/할당 상태를 먼저 복구한다.

## 관측과 범위

- Gateway: `gateway_deployment_guidance_requests_total`, `gateway_datagsm_ingress_total`, `gateway_deployment_state_refresh_failures_total`, `/internal/deployment-state`의 `services`와 `inflight`.
- Authorization: `cowork_authorization_webhook_stream_connected`, `..._entries`, `..._pending`, `..._oldest_seconds`, `..._failures_total`.
- Redis: 사용량/`maxmemory`, AOF 쓰기 상태, 디스크 여유. 내용이 잘못됐거나 처리 시점에 기존 30일 수용 기한을 넘긴 서명된 항목도 Stream에 남으며 자동 길이 제한으로 삭제하지 않는다. 경보가 발생하면 이벤트를 대사하고 수동 복구한다.
- Redis 자체가 중단되면 활성 Gateway는 마지막 확인 상태를 유지한다. 새 Gateway는 초기 Redis 동기화 전까지 준비 완료가 되지 않는다. DataGSM은 Redis 접수 실패 시 Authorization의 기존 MySQL 경로로 보내고, 둘 다 실패하면 503을 반환한다. 이 경우 발신자의 유한한 재시도 시간 안에 복구가 필요하다.
- data 단계의 DB·Redis 재시작은 업무 앱별 배포 플래그보다 먼저 수행된다. 이 단계의 연결 중단은 서비스별 HTTP 배포 안내 보장 범위에 포함되지 않는다.
