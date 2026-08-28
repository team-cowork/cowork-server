# Socket.IO Redis adapter 준비 상태와 복구 보장

- **서비스**: cowork-chat
- **우선순위**: 🔴 높음
- **현재 상태**: Redis pub/sub이 5초 안에 준비되지 않으면 Socket.IO가 in-memory adapter로 고정되고 readiness는 별도 Redis 연결만 확인함

## 문제

`cowork-chat/src/common/adapter/redis-io.adapter.ts`의 `RedisIoAdapter.connectToRedis`는 Redis pub/sub client 두 개의 `ready`를 최대 5초 동안 기다린다. 첫 오류나 timeout이 발생하면 예외를 밖으로 전달하지 않고 `adapterConstructor`를 비운 채 반환하며, 이후 `createIOServer`는 기본 in-memory adapter를 사용한다. 늦게 Redis가 복구되어도 초기 `Promise.race` 성공 경로가 다시 실행되지 않아 해당 프로세스에는 Redis adapter가 설치되지 않는다.

`GET /health/ready`는 `RedisRateLimiter.ping`이 소유한 별도 Redis client를 확인한다. 이 연결이 정상이어도 Socket.IO pub/sub client 한쪽 또는 양쪽이 준비되지 않았을 수 있고, `RedisIoAdapter`는 자신의 상태를 readiness에 제공하지 않는다. 다중 replica에서는 이 상태의 인스턴스가 정상으로 등록되면서 다른 replica에 연결된 소켓으로 room broadcast와 강제 room 해제를 전달하지 못한다.

pub/sub client는 `connectToRedis`의 지역 변수로만 유지되어 애플리케이션 종료 시 명시적으로 닫히지 않는다. 초기 성공 후 연결이 끊기면 ioredis 재연결 동작에 의존하지만 두 client의 상태 변화, adapter의 실제 복구 완료, 장시간 degraded 상태를 애플리케이션 lifecycle과 readiness가 추적하지 않는다.

## 상태와 트래픽 정책

| 상태 | 판정 기준 | WebSocket·readiness 정책 |
|------|-----------|---------------------------|
| `CONNECTING` | pub/sub 중 하나 이상이 아직 `ready`가 아님 | WebSocket 가입을 받지 않고 readiness를 내림 |
| `READY` | pub/sub 모두 준비되고 Redis adapter가 서버에 설치됨 | 다중 replica 실시간 트래픽을 허용함 |
| `DEGRADED` | 준비 후 연결 상실 또는 adapter 오류 발생 | readiness를 내리고 신규 연결·room 가입을 차단함 |
| `IN_MEMORY` | 명시적인 단일 인스턴스 개발 모드 | 상태를 응답에 표시하고 운영 다중 replica에서는 허용하지 않음 |
| `STOPPED` | 애플리케이션 종료 중 | 재연결을 중단하고 두 client를 닫음 |

운영에서 timeout에 의한 암묵적 in-memory fallback을 허용하지 않는다. HTTP health 응답 자체는 제공하되 Redis adapter가 복구되어 `READY`가 되기 전에는 Eureka 등록과 WebSocket 트래픽을 열지 않는 방향으로 startup 정책을 맞춘다.

## 할 일

### lifecycle과 복구

- `RedisIoAdapter`가 pub/sub client를 필드로 소유하고 연결 상태와 마지막 오류를 상태 머신으로 관리하게 한다.
- 초기 timeout 이후에도 제한된 backoff로 연결을 계속 시도하고, 두 client가 준비된 뒤에만 Redis adapter 설치 완료로 전환한다.
- 초기 in-memory 서버에서 Redis adapter로 동적으로 전환할지, adapter 준비 전 Socket.IO 서버 생성을 보류할지 결정하고 연결 중인 소켓의 일관성을 검증한다.
- 준비 후 pub/sub client 한쪽이 끊기면 상태를 즉시 `DEGRADED`로 바꾸고 양쪽 재연결과 adapter 복구를 확인한다.
- 애플리케이션 종료 시 reconnect timer를 취소하고 pub/sub client를 모두 종료한다.
- 단일 인스턴스 개발에서만 사용할 명시적 in-memory 모드를 추가하고 자동 fallback과 구분한다.

### readiness와 관측

- `RedisIoAdapter` 상태를 `HealthController.ready`의 별도 dependency로 노출한다.
- rate limiter Redis PING과 Socket.IO pub/sub 준비 상태를 서로 다른 필드로 응답한다.
- `cowork-chat/src/main.ts`의 Eureka 등록 조건과 `ChatGateway`의 연결·packet middleware가 adapter 준비 상태를 함께 사용하게 한다.
- 상태 전환, 연속 degraded 시간, 재연결 횟수, pub/sub client별 오류를 지표와 제한된 경고로 남긴다.
- 실제 적용 중인 adapter 종류와 다중 replica 허용 여부를 startup 로그에서 확인할 수 있게 한다.

## 검증

- Redis가 5초보다 늦게 기동해도 프로세스 재시작 없이 Redis adapter가 설치되고 readiness가 회복되는지 검증한다.
- pub client와 sub client를 각각 끊었을 때 readiness가 내려가고 복구 후 다시 올라오는지 검증한다.
- rate limiter client만 정상인 경우 `GET /health/ready`가 Socket.IO adapter 장애를 숨기지 않는지 검증한다.
- 두 `cowork-chat` replica에 나누어 연결한 소켓 사이에서 message broadcast와 remote room 해제가 전달되는지 검증한다.
- adapter가 `DEGRADED`인 동안 신규 WebSocket 연결과 room 가입이 허용되지 않는지 검증한다.
- 정상 종료 후 pub/sub 연결과 재연결 timer가 남지 않는지 확인한다.

## 완료 조건

- 운영 다중 replica에서 Redis adapter가 준비되지 않은 인스턴스는 readiness를 통과하지 않는다.
- 초기 timeout이나 일시적인 Redis 장애 뒤 프로세스 재시작 없이 adapter가 복구된다.
- readiness가 rate limiter 연결과 Socket.IO pub/sub 연결을 독립적으로 표시한다.
- Redis adapter의 상태 변화와 복구 실패를 운영 지표 및 로그로 확인할 수 있다.
- 애플리케이션 종료 시 adapter가 소유한 Redis client와 timer가 모두 정리되어 있다.
