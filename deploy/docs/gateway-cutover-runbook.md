# Gateway 프록시와 배포 상태 운영

## 운영 전제와 호스트 전환

Gateway VM은 8 GB이며 NGINX가 기존 8080 진입점을 담당한다. Gateway 할당은 고정 포트 21000/21001을 교대로 사용한다. 외부 HTTPS 종료 지점과 VM 주소는 유지한다. Gateway와 Authorization은 하나의 저장소 수준 GitHub `DATAGSM_WEBHOOK_SECRET`을 사용하며, CD가 고정된 Vault 버전의 값이 같은지 확인한다.

8080을 직접 사용하는 기존 Gateway를 정리할 때는 배포 잠금을 잡고 Nomad job을 백업한 뒤 종료·제거한다. 이전 할당과 포트가 해제되면 NGINX를 활성화한다. 검증된 Gateway가 없을 때 `/etc/cowork/gateway-upstream.conf`는 `http://10.0.0.127:9`를 가리킨다. 이 상태에서는 API 요청이 실패하며, 새 Gateway와 Authorization 소비자가 포함된 정식 CD가 완료되어야 요청을 다시 받는다. 기존 8080 job 선언으로 배포하면 NGINX와 충돌하므로 이관된 선언을 사용한다.

호스트 전환은 운영 작업으로 끝내며, CD에 구형 8080 호환이나 전환 후 제거할 코드를 두지 않는다. 불명확한 실패는 Nomad 할당과 프록시 상태를 확인해 수동 복구한다.

## 정식 CD 동작

Gateway job이 없거나 완전히 중지되어 있으면 프록시를 닫고 업무 앱과 Authorization의 Redis 소비자를 먼저 준비한다. 새 Gateway를 21000에 생성한 뒤 Nomad/Consul 준비, Redis 배포 상태 동기화, 전체 업무 upstream 응답을 확인하고 NGINX를 전환한다. 이 과정에서 남긴 서비스별 복구 상태는 실제 서비스 준비를 확인한 뒤 해제한다.

Gateway가 실행 중이면 변경되는 업무 서비스에만 배포 상태를 기록한다. AOF 반영과 Gateway의 상태 수신, 진행 중인 일반 HTTP 요청 배출을 확인한 뒤 앱을 교체한다. 서비스 준비와 Gateway upstream 응답을 확인하고 상태를 해제한다. Authorization은 Redis 소비자 연결을 확인하며, Stream 항목이나 pending이 0이 될 때까지 배포를 막지 않는다.

Gateway 변경은 반대 포트의 canary를 검증한 뒤 NGINX를 전환하고 기존 worker의 연결이 빠지면 승격한다. WebSocket/SSE는 일반 HTTP 배출 대상에서 제외되며, 최대 300초의 proxy worker 종료 시간 이후 재연결될 수 있다. 최종 검증이 끝나야 배포 성공 기록과 Release 게시가 진행된다.

공유 Redis의 2 GB VM, `maxmemory=256mb`, Nomad 512 MiB 상한은 유지한다. 사용률 75% 경보는 관측용이며 CD 시작 조건이 아니다. 배포는 실제 상태 쓰기·AOF 확인이나 서비스 준비 확인이 실패하면 중단한다.

## 중단된 실행 복구

보호된 배포 runner의 `deploy.lock`이 동시 실행을 막는다. 중단된 실행의 `deploying` 상태는 다음 실행에서 기존 runId로 `recovering`으로 바꾼다. 변경 서비스는 새 실행 ID로 인수하고, 나머지 상태도 현재 서비스 준비와 Gateway upstream 응답을 확인한 뒤 해제한다.

서비스가 준비됐지만 상태만 남았다면 보호된 runner에서 개별 복구한다.

```bash
flock "$COWORK_PLATFORM_STATE_DIR/deploy.lock" bash deploy/production/apply.sh deploy/production/releases.json --recover-state cowork-authorization
```

마지막 인자를 다른 `cowork-<service>`로 바꿀 수 있다. 준비·upstream 확인이나 Authorization 소비자 연결이 실패하면 상태를 지우지 않는다. 이미 접수한 웹훅이 남아 있을 수 있으므로 Stream을 비우거나 소비자 없는 앱으로 되돌려 문제를 숨기지 않는다.

canary 도중 중단되어 Gateway 실행 할당이 둘 남으면 자동 재시도 전에 두 할당의 버전·포트·health와 NGINX 대상을 대조한다. 검증된 새 후보가 서비스 중이면 해당 deployment를 승격한다. 구 할당이 서비스 중이면 후보를 검증한 뒤 전환하거나 실패한 후보를 정리한다. `gateway-verify.yml`로 8080 응답과 대상 포트 일치를 확인한 후 CD를 재개한다.

## 관측과 범위

- Gateway: 배포 안내 응답 수, DataGSM 접수 결과, Redis 상태 갱신 실패, `/internal/deployment-state`의 서비스 상태와 진행 중인 요청 수.
- Authorization: Stream 소비자 연결, 항목 수, pending, 가장 오래된 항목의 나이, 처리 실패. 정상 유입 중에는 항목 수가 0이 아니어도 된다. 적체가 줄지 않거나 나이가 증가하면 소비자와 DB 상태를 확인한다.
- Redis: 사용량/`maxmemory`, AOF 쓰기 상태와 디스크 여유. 공유 Redis 접속 서비스는 웹훅 원문을 읽거나 키를 수정할 수 있다는 운영 전제로 사용한다.
- 유효한 서명을 가졌어도 내용이 잘못됐거나 기존 30일 수용 기한을 넘긴 항목은 자동 삭제하지 않는다. 원문을 로그에 남기지 않고 이벤트를 대사해 수동 복구한다.
- Redis 조회 실패 시 활성 Gateway는 마지막 확인 상태를 유지한다. 새 Gateway는 초기 동기화 전 요청을 전달하지 않는다. 웹훅은 Redis 접수가 실패하고 Authorization이 배포 중이 아님을 확인한 경우 직접 MySQL 접수로 전달한다. 두 경로 모두 실패하면 503을 반환하므로 발신자의 재시도 시간 안에 복구해야 한다.
- data 단계의 DB·Redis 재시작과 Gateway가 없는 동안의 중단은 서비스별 HTTP 배포 안내 보장 범위에 포함되지 않는다.
