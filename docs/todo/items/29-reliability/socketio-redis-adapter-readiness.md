# Socket.IO Redis adapter 준비 상태와 복구 보장

- **서비스**: cowork-chat
- **우선순위**: 🔴 높음
- **현재 상태**: adapter 설치·재연결·readiness·socket 차단은 구현되어 있으나 등록 후 Eureka 상태 동기화와 여러 replica의 운영 검증이 남아 있다.

## 문제

현재 adapter는 서버 생성 때 설치하고 Redis 연결 상태에 따라 readiness·WebSocket 트래픽을 제어한다.
초기 timeout 뒤 in-memory로 고정되던 이전 설명은 현재 코드에 해당하지 않는다.

Eureka는 최초 준비 완료 뒤 `UP`으로 등록하고 heartbeat를 보낸다. adapter가 나중에 degraded가
되어도 상태 변경·등록 해제를 수행하지 않는다. `healthCheckUrl` 등록과 readiness의 `503`만으로
Gateway의 선택 대상에서 제거됐다고 판정할 수 없다.
늦은 Redis 기동·한쪽 client 단절·구독 복구와 remote 전달의 운영 결과도 저장소에서 확인할 수 없다.

## 코드 근거

- [Redis adapter](../../../../cowork-chat/src/common/adapter/redis-io.adapter.ts#L116): 서버 생성 때 adapter를 설치하고 pub/sub 연결 상태·재연결·종료를 관리한다.
- [Eureka 등록 시점](../../../../cowork-chat/src/main.ts#L159): 최초 준비를 기다려 등록하지만 일반 adapter degraded 상태에 대한 deregister 경로는 없다.
- [Eureka 상태](../../../../cowork-chat/src/eureka/eureka-client.ts#L59): UP 등록과 heartbeat를 수행하며 readiness 상태를 반영하는 API 호출은 없다.

## 할 일

- Redis 지연 기동 후 프로세스 재시작 없이 pub/sub 연결과 전달이 복구되는지 확인한다.
- pub·sub client를 각각 끊어 readiness·신규 연결·packet 차단과 복구를 확인한다.
- 두 replica에서 broadcast·room 해제·remote socket 조회를 확인한다.
- 준비 상태 변경을 Eureka 상태 변경·등록 해제 또는 동등한 라우팅 제외로 연결하고 복구 시 재등록한다.
- 장애 동안 이미 등록된 앱이 Gateway의 선택 대상에서 실제로 제외되는지 확인한다.
- 종료 후 재연결·구독이 남지 않는지 확인하고 지표·알림 기준을 정한다.

## 검증

- client 상태와 adapter 설치·readiness를 정적으로 대조한다.
- 연결 장애·복구·여러 replica 전달은 수동 운영 점검으로 확인한다.
- degraded 시간·오류·재연결 지표와 실제 트래픽 차단 결과를 기록한다.

## 완료 조건

- 지연 기동과 client 단절 후 같은 프로세스의 Redis adapter가 정상 전달을 회복한다.
- 준비되지 않은 인스턴스로 실시간 트래픽이 유입되지 않는다.
- 여러 replica의 전달·회수와 종료 결과가 기록되어 있다.
