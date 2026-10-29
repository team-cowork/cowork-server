# #662 배포 안내와 Gateway 마지막 전환 계획

상태: 코드 구현과 로컬 검증 완료, 운영 적용·격리 부하 관측 대기. 기준: 2026-10-29 코드와 운영 VM 확인. Gateway VM에는 NGINX 패키지와 비활성 정적 설정을 준비했다. 기존 Gateway의 8080 헬스체크는 200이고 `/api/health`의 업무 서비스 11개가 모두 `UP`이다. VM 증설, 8080 트래픽 전환, 애플리케이션 배포는 아직 수행하지 않았다. Redis 메모리 증설은 이번 계획에서 제외한다.

## 확인한 현재 상태

- 현재 배포는 Nomad 기반이다. `deploy/production/apply.py`가 foundation → data → apps → edge를 적용하므로 Gateway가 마지막 단계에 있기는 하지만, 서비스별 배포 안내와 실패 시 전환 중단·복귀 절차는 없다. CD의 `publish`는 `apply` 성공을 기다리지 않는다.
- Gateway는 단일 할당, 고정 호스트 포트 8080, canary 0, `auto_revert=false`다. 실제 운영 VM은 4 vCPU·약 4 GiB RAM, swap 없음. 앱은 약 1 GiB/2 GiB 한도, Envoy는 약 19 MiB/128 MiB, Alloy는 약 160 MiB/256 MiB를 사용했다. 사용자 결정에 따라 목표 VM은 **8 GB**로 잡는다.
- Redis VM은 약 2 GiB RAM, swap 없음, 가용 메모리 약 1.3 GiB다. Redis 메모리 사용량 약 4.2 MiB, 관측 최고 약 6.3 MiB, 컨테이너 약 18 MiB/512 MiB, AOF 약 10.3 MiB, 디스크 여유 13 GiB, AOF 쓰기 상태 정상이다. 현재 `maxmemory=256mb`, `noeviction`, AOF `everysec`다. 현재 부하에 비해 설정 한도에 여유가 크다.
- Authorization의 DataGSM 웹훅은 서명 검증 후 MySQL inbox와 outbox를 한 트랜잭션으로 기록한다. 그러나 Authorization이 내려가면 수신 자체가 불가능하다. DataGSM 재시도 횟수는 유한하므로 배포가 재시도 기간을 넘으면 이벤트가 사라질 수 있다.
- 운영 Authorization의 현재 웹훅 요청 카운터에는 표본이 없고 Prometheus의 최근 24시간·7일 증가량 조회에도 시계열이 없었다. 이를 수신량 0의 보증으로 해석하지 않고 첫 배포에서 적체량·나이를 관측한다.
- Gateway의 DataGSM 경로는 `/api/authorization/events/datagsm`이며 현재 Authorization으로 직접 전달한다. 서비스 목적지 메타데이터가 일부 경로에만 있어 배포 대상은 전체 라우트 URI 기준으로 판정해야 한다.

## 결정과 보장 범위

1. Redis 조회가 일시 실패해도 **기존 Gateway는 서비스별 마지막 확인 상태를 유지**한다. 새 Gateway는 최초 상태 동기화에 성공하기 전 준비 완료로 취급하지 않는다. CD가 상태 기록과 재조회로 확인하지 못하면 업무 서비스 중지를 시작하지 않는다.
2. Authorization 배포 중 DataGSM 웹훅은 영속 접수 경로를 제공한다. Gateway가 서명·크기를 확인한 뒤 원본 본문을 Redis Stream에 기록하고, **같은 Redis 연결에서 `WAITAOF 1 0`이 성공한 경우에만 200**을 반환한다. Authorization이 재개되면 소비자가 서명을 재검증한 후 기존 `ProcessEvent`와 MySQL inbox/outbox에 반영하고 Stream 항목을 확인 처리한다. 배포 기간에만 분기하기보다 모든 DataGSM 웹훅을 이 경로로 접수하면 상태 전환 경계의 수신 누락을 줄일 수 있다.
3. Redis 접수 실패 시 Authorization이 정상이라면 기존 직접 접수로 되돌릴 수 있다. Authorization도 접수할 수 없으면 503을 반환하고 배포를 중단·복구한다. 단일 Redis VM 및 디스크까지 동시에 장애가 나거나 발신자 재시도 시간이 끝난 상황에서 절대적 무손실은 보장할 수 없다. 이 위험은 운영 경보와 수동 대사 절차로 관리한다.
4. Gateway VM의 기존 외부 8080은 호스트 NGINX가 받는다. Gateway Nomad 할당은 동적 포트 재할당으로 프록시 대상이 어긋나지 않도록 **고정 21000/21001을 교대로 사용**한다. **최초 4 GB VM 전환은 사용자가 현재 중단을 허용했으므로 구형의 8080을 해제한 뒤 신형 21000과 NGINX를 시작**한다. 이 단계에서 새 Gateway가 웹훅을 접수하기 시작한 후에는 소비자가 없는 구 Gateway로 자동 복귀하지 않고 새 Authorization 소비자를 복구한다. 이후 Gateway VM이 8 GB가 되면 `count=1, canary=1, auto_promote=false`로 구형과 신형을 함께 띄운다. 신형의 라우팅·Redis 상태 동기화·Nomad canary 준비 완료를 확인하고 NGINX를 재적용해 신규 요청을 전환한다. 구형 연결을 비운 뒤 promote·중지한다.
5. 업무 서비스 VM에는 구형과 신형 업무 컨테이너를 함께 실행하지 않는다. Gateway 단독 배포, Gateway 미변경 배포, 정상 배포와 롤백, `check_only`를 각각 구분한다. 현재 운영 매니페스트에서 이 브랜치까지 `.dockerignore`를 포함한 변경이 누적되어 **첫 CD는 Authorization을 포함한 모든 업무 앱을 갱신할 수 있다**. 첫 전환은 변경 앱 전체에 플래그를 설정하고 실패 시 전체를 복구 대상으로 유지한다.

## 메모리와 영속성 예산

- Gateway 8 GB 목표: Gateway 앱 2개 × 2048 MiB, Envoy 2개 × 128 MiB, Alloy 256 MiB, node exporter 96 MiB, NGINX 예산 256 MiB, OS·Nomad 예약 1024 MiB = **5984 MiB**. 8 GB를 십진 용량으로 보아도 약 1.6 GiB가 남는다. 이 값은 제한값 합계이므로 실제 피크 RSS와 AOF·네트워크 부하를 관측해 확정한다.
- Redis는 **현행 2 GB VM, `maxmemory=256mb`, Nomad `memory=256`·`memory_max=512` MiB를 유지**한다. 현재 Redis 사용량은 `maxmemory`의 약 1.6%, 관측 최고도 약 2.5%다. Gateway·Chat·GitHub·Preference·User 등이 Redis를 공유하며 대부분의 캐시·제한 키는 만료되지만, 일부 키는 만료되지 않는다. 추가 웹훅 적체량은 아직 측정하지 않았으므로 현재 여유를 새 기능의 보장 용량으로 간주하지 않는다. 1 MiB 본문만 계속 들어오면 기존 키와 Redis 오버헤드를 제외하기 전에도 256 MiB 한도에는 250건 미만만 들어간다. Redis 프로세스가 AOF 재작성 중 사용하는 메모리는 `maxmemory`보다 클 수 있다. Redis·Envoy·Alloy·exporter의 현재 컨테이너 상한 합계는 992 MiB이고 Nomad의 호스트 예약 512 MiB까지 합치면 1504 MiB로, 약 1963 MiB VM에서 약 459 MiB의 보수적 여유가 있다. 이 합계는 실제 사용량이 아니라 구성상 상한이다.
- Stream은 `MAXLEN`으로 미처리 항목을 자동 삭제하지 않는다. `noeviction`을 유지하고, 한도·디스크 부족 시 새 접수를 503으로 실패시킨다. 사용자 결정에 따라 원본 본문을 공유 Redis에 저장한다. Redis는 여러 서비스가 공유하고 키별 접근 제한이 없어 연결된 서비스가 원문을 읽거나 Stream·배포 상태 키를 수정할 수도 있다. 따라서 Redis 접속 서비스 전체를 이 상태의 신뢰 경계로 취급하며 원문은 AOF에도 남는다. 원문과 서명을 로그에 남기지 않고, MySQL 기록이 확인된 항목만 삭제한다. 서명은 유효하나 내용이 잘못됐거나 처리 시점에 기존 30일 수용 기한을 넘긴 항목은 보존하고 지표로 경보해 수동 복구한다.
- AOF `everysec`에서 `XADD` 성공만으로 200을 반환하면 최근 기록이 손실될 수 있다. `WAITAOF`의 로컬 fsync 확인 결과를 검사하고 시간 초과와 AOF 오류를 실패로 처리한다. 이는 단일 VM의 디스크 소실·손상을 해결하지는 않는다.

## 구현 순서

1. **기준값과 배포 구조 고정**: Gateway·Redis의 피크 메모리, 장시간 연결 수, DataGSM 웹훅 크기·빈도, 서비스별 배포 시간 분포를 수집한다. 현재 이력만으로 최대 버퍼 크기나 HTTP 실패율을 단정하지 않는다.
2. **용량 확인**: Gateway는 8 GB로 잡고 Redis 메모리 설정은 변경하지 않는다. 웹훅 크기·빈도와 최대 배포 중단 시간으로 필요한 적체량을 산정한다. 현행 256 MiB 한도에서 배포 상태와 기존 Redis 사용분을 제외한 뒤에도 필요한 적체량을 수용하는지 확인한다. 부족하다면 이번 Redis Stream 방식의 수용 조건이 충족되지 않은 것이므로, Redis 증설을 전제하지 않고 기존 MySQL에 직접 영속 접수하는 별도 수신 경로 등 대안을 다시 선택한다.
3. **웹훅 영속 접수**: Gateway의 정확한 DataGSM 경로만 수신한다. HMAC, 1 MiB 본문 한도, 요청 메서드, 중복 이벤트 ID를 처리한다. Gateway Vault 경로에 DataGSM 서명 키를 선언하고 Authorization에는 Redis용 Consul upstream·접근 정책을 추가한다. Gateway의 현재 Authorization 웹훅 CircuitBreaker는 접수 경로에서 제거하고, 배포 안내 필터보다 웹훅 접수를 먼저 처리한다. Stream 쓰기와 fsync 확인 후 응답하고, Authorization 소비자는 재시작 후 pending 항목을 회수해 MySQL 기록 뒤 ACK한다. 실패 항목은 보존·경보하고, 재처리 시 inbox 중복 방지를 사용한다. Redis가 정상일 때 Authorization 배포가 재시도 기간을 넘어도 항목이 보존되는지 운영 지표로 확인한다.
4. **배포 상태와 응답**: Redis에 서비스별 상태·배포 실행 ID를 저장한다. 쓰기·해제는 실행 ID 조건부로 수행한다. Gateway는 실제 라우트 URI로 대상 서비스를 찾아 새 HTTP 요청만 `503` + `CommonApiResponse.data.reason=SERVICE_DEPLOYING`으로 응답하고 `Cache-Control: no-store`를 붙인다. 실패 후 복구 대기는 별도 사유 값으로 구분한다. 기존 JWT·CORS·웹훅 처리 순서를 보존한다.
5. **CD 단계 제어**: 변경된 업무 서비스 전체를 중지하기 전에 플래그 기록·전파 확인 → 기존 요청 drain → 기존 단일 할당 교체 → Nomad/Consul 준비와 Gateway `/api/health` 전달 확인 → 전체 준비 및 Authorization 대기열 처리 뒤 플래그 해제 순서로 진행한다. 실패·취소·시간 초과는 이전 버전 복구를 확인하기 전 해제하지 않는다. 중단된 실행의 플래그는 실행 잠금 아래 `recovering`으로 바꾸고, 실제 할당 상태와 대조해 복구한다. `publish`, 완료 알림, 이전 인증정보 정리는 최종 Gateway 전환 성공에 의존하게 한다.
6. **Gateway 전환**: 최초 8080 포트 이관은 현재 4 GB VM의 자동 CD stop/start 절차로 수행하고 중단 시간을 기록한다. 이미 NGINX 패키지와 정적 설정은 운영 Gateway VM에 설치·검증했으며 서비스는 비활성 상태다. 첫 트래픽 전환 뒤에는 수락된 웹훅이 남을 수 있으므로 자동으로 소비자 없는 구 버전으로 돌아가지 않는다. 이후 8 GB VM에서는 21000/21001의 반대 포트에 새 canary를 띄우고, NGINX 설정 검증·reload, 구형 연결 drain, promote 순서로 전환한다. WebSocket/SSE는 재연결 계약과 최대 drain 시간을 별도 확정한다.

## 완료 확인과 미결 사항

- 핵심 배포 대상 판정, 실행 ID 조건부 해제, 마지막 확인 상태, 웹훅 중복 처리·ACK 조건은 단위 테스트로 검증하고 최종 변경에 남긴다. 사용자 설명에 따라 개발 중에는 임시 통합·회귀 테스트를 작성·실행할 수 있다. 격리 환경에서 #662의 HTTP 실패 조건과 웹훅 재처리를 확인한 뒤 **임시 테스트 파일을 최종 커밋 전에 삭제**한다. 각 모듈의 기본 테스트 명령은 그대로 두며 테스트 허용 목록을 추가하지 않는다.
- 릴리스 성공 시 배포 대상에만 안내가 나타나고 정상 서비스는 전달되어야 한다. 상태가 확인되지 않으면 업무 서비스 중지가 시작되지 않아야 한다. 새 Gateway는 상태 동기화 전 전환되지 않아야 한다. Authorization 재시작 후 미처리 Stream 건수가 0으로 돌아와야 한다.
- 수치가 필요한 결정: 최대 허용 웹훅 적체 시간·건수, HTTP drain 시간, WebSocket/SSE 재연결 시간, Gateway 전환 실패율을 측정할 요청 조건. 이 수치는 실제 관측값으로 확정한다.

## 근거

- [이슈 #662](https://github.com/team-cowork/cowork-server/issues/662)
- [Redis AOF와 `everysec`](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/)
- [`WAITAOF` 명령](https://redis.io/docs/latest/commands/waitaof/)
- [Nomad canary 업데이트](https://developer.hashicorp.com/nomad/docs/job-specification/update)
- [NGINX reload](https://nginx.org/en/docs/control.html)
- [DataGSM 이벤트 재시도](https://docs.datagsm.kr/event)
