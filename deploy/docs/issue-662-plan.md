# #662 배포 안내와 Gateway 전환

기준: 2026-10-29 코드와 운영 VM 확인. Gateway의 8 GB 증설을 확인했고, 기존 8080 Nomad job을 백업·제거한 뒤 NGINX 활성화와 21000/21001 방화벽 적용을 완료했다. 현재 Gateway 앱은 중지되어 API는 502를 반환한다. 이 변경을 포함한 정식 CD가 앱을 배포하고 검증된 Gateway로 요청 경로를 연다. Redis 메모리 설정은 유지했다. 운영 절차는 [Gateway 운영 문서](gateway-cutover-runbook.md)에 정리한다.

## 확인한 운영 조건

- 기존 Authorization은 서명된 DataGSM 이벤트를 MySQL inbox/outbox에 함께 기록하지만, 앱이 내려가면 요청을 받지 못한다. 배포 중에도 접수할 수 있는 경로가 필요하다.
- Gateway VM의 확인된 `MemTotal`은 8,253,252 kB다. Gateway 두 할당과 Envoy, 호스트 에이전트, NGINX를 함께 운영하는 8 GB 구성을 사용한다.
- Redis VM은 약 2 GiB이며 확인 당시 사용량은 약 4.3 MiB, 관측 최고는 약 6.3 MiB였다. `maxmemory=256mb`, `noeviction`, AOF `everysec`, Nomad 최대 512 MiB를 유지한다. AOF 쓰기 상태는 정상이었고 디스크 여유는 약 13 GiB였다.
- DataGSM 유입량을 계산할 최근 24시간·7일 메트릭 표본이 없었다. 현재 Redis의 여유는 확인했으나, 웹훅을 얼마 동안 버틸 수 있는지는 실제 적체량과 항목 나이로 관측한다.

## 결정과 보장 범위

1. 활성 Gateway는 Redis 조회가 실패해도 서비스별 마지막 확인 상태를 유지한다. 새 Gateway는 초기 상태 동기화 전에 업무 요청을 전달하지 않는다. 운영 중인 서비스 교체는 상태 기록·전파와 진행 중인 HTTP 요청 배출을 확인한 뒤 시작한다.
2. DataGSM 웹훅은 Gateway에서 서명·크기를 확인하고 원본 본문을 Redis Stream에 저장한다. 같은 연결의 `WAITAOF`로 로컬 fsync를 확인한 뒤 200을 반환한다. Authorization 소비자는 서명을 재검증하고 기존 MySQL 처리에 성공한 항목만 확인 처리·삭제한다. 재처리의 중복은 기존 inbox로 막는다.
3. Redis 접수 실패 시 마지막 확인 상태에 Authorization 배포 표시가 없으면 직접 접수를 시도한다. 직접 접수도 실패하면 503을 반환한다. 단일 Redis 디스크 소실이나 발신자의 재시도 기간을 넘긴 동시 장애까지 해결하는 구조는 아니다.
4. NGINX가 8080을 맡고 Gateway는 고정 21000/21001을 교대로 사용한다. 검증된 Gateway가 없으면 프록시를 닫고 업무 앱·소비자부터 준비한다. 정상 운영 중 Gateway 교체는 새 canary 검증, 프록시 전환, 기존 연결 배출, 승격 순서로 진행한다.
5. 업무 앱은 단일 할당을 교체한다. 실제 변경되는 서비스에만 배포 안내를 적용하며, 준비 상태와 upstream 연결을 확인한 뒤 해제한다. Authorization 소비자 연결은 확인하되 Stream이 비어 있음을 요구하지 않는다.

## 메모리와 영속성

Gateway 앱 두 개와 Envoy, 호스트 에이전트, NGINX 및 OS 예약을 합친 구성 예산은 약 5984 MiB다. 실제 피크 사용량은 운영 지표로 확인한다. Redis는 현재 한도와 VM 크기를 유지하며, 사용률 75%는 경보 기준으로만 사용한다. 특정 사용률이나 정확한 메모리 한도를 CD 진입 조건으로 강제하지 않는다.

Stream은 미처리 항목을 길이 제한으로 삭제하지 않는다. 메모리·디스크 부족이나 AOF 실패는 접수 실패로 처리한다. 공유 Redis의 다른 접속 서비스도 원문을 읽거나 배포 상태·Stream 키를 수정할 수 있으며, 이 서비스들을 같은 신뢰 범위로 취급한다. 원문과 서명은 로그에 남기지 않는다. 서명이 유효해도 처리할 수 없는 항목은 보존하고 경보 후 대사한다.

## 적용과 검증

호스트 전환은 기존 8080 Gateway job을 백업·제거하고 NGINX를 활성화하는 운영 작업으로 완료한다. 다음 정식 CD는 Gateway가 없는 상태를 인식해 앱과 소비자, 새 Gateway를 준비한 뒤 요청 경로를 연다. 구형 포트 호환이나 전환 후 제거할 임시 분기는 남기지 않는다. 전환 중 실패는 실제 할당·프록시 상태를 확인해 수동 복구할 수 있다.

핵심 업무 로직의 단위 테스트만 작성·실행한다. 통합·회귀 테스트는 작성하거나 실행하지 않으며, 각 모듈의 기본 CI 테스트 명령과 커버리지 수집은 유지한다. 배포 정의는 문법과 native offline 검증을 수행한다.

운영에서는 변경 대상만 배포 안내를 받는지, 상태 확인 없이 앱 교체가 시작되지 않는지, 새 Gateway가 검증 전에 노출되지 않는지 확인한다. 웹훅 소비자의 연결과 적체 나이를 함께 관측하며, 지속적인 유입 중 대기열이 0이 되는 것을 성공 조건으로 삼지 않는다.

## 근거

- [이슈 #662](https://github.com/team-cowork/cowork-server/issues/662)
- [Redis AOF와 `everysec`](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/)
- [`WAITAOF` 명령](https://redis.io/docs/latest/commands/waitaof/)
- [Nomad canary 업데이트](https://developer.hashicorp.com/nomad/docs/job-specification/update)
- [NGINX reload](https://nginx.org/en/docs/control.html)
- [DataGSM 이벤트 재시도](https://docs.datagsm.kr/event)
