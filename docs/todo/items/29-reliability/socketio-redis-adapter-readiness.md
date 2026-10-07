# Socket.IO Redis adapter 준비 상태와 복구 보장

- **서비스**: cowork-chat
- **우선순위**: 🔴 높음
- **현재 상태**: adapter 설치·재연결·readiness·socket 차단, 등록 후 Eureka 상태 동기화와 장애 알림 규칙은 구현되어 있으며 Gateway 제외 확인과 여러 replica의 운영 검증이 남아 있다.

## 문제

현재 adapter는 서버 생성 때 설치하고 Redis 연결 상태에 따라 readiness·WebSocket 트래픽을 제어한다.
초기 timeout 뒤 in-memory로 고정되던 이전 설명은 현재 코드에 해당하지 않는다.

Eureka는 최초 준비 완료 뒤 `UP`으로 등록한다. 등록 후 adapter 준비 상태가 바뀌면 lease는 유지한 채
status override를 `OUT_OF_SERVICE`로 바꾸고, 복구되면 override를 지워 `UP`으로 되돌린다.
등록 해제 대신 override를 쓰는 이유는 Prometheus Eureka discovery가 장애 중에도 수집을 유지하기 때문이다.
Gateway는 Eureka client 기본값(`filter-only-up-instances`)으로 `UP`만 선택하지만, registry fetch·
서버 응답 캐시·LoadBalancer 캐시를 거쳐 반영되므로 실제 제외 시점은 운영에서 확인해야 한다.
늦은 Redis 기동·한쪽 client 단절·구독 복구와 remote 전달의 운영 결과도 저장소에서 확인할 수 없다.

## 코드 근거

- [Redis adapter](../../../../cowork-chat/src/common/adapter/redis-io.adapter.ts#L131): 서버 생성 때 adapter를 설치하고 pub/sub 연결 상태·재연결·종료를 관리한다.
- [준비 상태 연결](../../../../cowork-chat/src/main.ts#L179): adapter 준비 상태 변화를 Eureka status로 전달한다.
- [Eureka 상태](../../../../cowork-chat/src/eureka/eureka-client.ts#L116): status 변경을 직렬화해 최신 상태만 반영하고, 실패는 다음 heartbeat에서 다시 맞춘다.
- [장애 알림](../../../../deploy/config/monitoring/prometheus/rules/application-alerts.yml#L93): DEGRADED 1분은 warning, 5분은 critical, 10분 안의 재연결 5회 초과는 warning으로 알린다.

## 할 일

- Redis 지연 기동 후 프로세스 재시작 없이 pub/sub 연결과 전달이 복구되는지 확인한다.
- pub·sub client를 각각 끊어 readiness·신규 연결·packet 차단과 복구를 확인한다.
- 두 replica에서 broadcast·room 해제·remote socket 조회를 확인한다.
- ~~준비 상태 변경을 Eureka 상태 변경·등록 해제 또는 동등한 라우팅 제외로 연결하고 복구 시 재등록한다.~~
- 장애 동안 이미 등록된 앱이 Gateway의 선택 대상에서 실제로 제외되는지와 반영 지연을 확인한다.
- 종료 후 재연결·구독이 남지 않는지 확인한다.
- ~~adapter 지표의 알림 기준을 정한다.~~ (DEGRADED 1분 warning·5분 critical, 재연결 10분 5회 초과 warning)

## 검증

- client 상태와 adapter 설치·readiness를 정적으로 대조하고, 준비 여부가 바뀔 때만 알리는지는 단위 테스트로 확인한다.
- 연결 장애·복구·여러 replica 전달은 수동 운영 점검으로 확인한다.
- degraded 시간·오류·재연결 지표와 실제 트래픽 차단 결과를 기록한다.

## 완료 조건

- 지연 기동과 client 단절 후 같은 프로세스의 Redis adapter가 정상 전달을 회복한다.
- 준비되지 않은 인스턴스로 실시간 트래픽이 유입되지 않는다.
- 여러 replica의 전달·회수와 종료 결과가 기록되어 있다.
