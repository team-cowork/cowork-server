# 멤버십 회수 시 WebSocket 구독 강제 해제

- **서비스**: cowork-chat
- **우선순위**: 🔴 높음
- **현재 상태**: 이벤트 회수·주기 로컬 재인가·readiness 보호는 구현되어 있으며 여러 replica·Redis 장애·재연결 경합의 운영 확인이 남아 있다.
- **관련 작업**: [Socket.IO Redis adapter 준비 상태와 복구 보장](../29-reliability/socketio-redis-adapter-readiness.md)

## 문제

채널·팀 탈퇴와 채널 삭제 뒤 room 해제, join·typing 재인가가 구현되어 있다.
Redis 발행 실패나 consumer 재시작 때 즉시 해제가 누락될 수 있어 주기 로컬 재인가도 사용한다.
이 경로는 projection이 준비되지 않았다는 이유로 권한 보유 소켓을 제거하지 않는다.

현재 Redis adapter 준비 상태가 readiness에 연결되어 있으나 실제 remote room 회수와
대규모 접속의 재인가 비용은 확인하지 않았다. 코드 존재만으로 모든 replica의 회수 완료를
판정할 수 없다.

## 할 일

- 같은 사용자의 여러 브라우저·replica에서 채널·팀 탈퇴와 채널 삭제 뒤 회수를 확인한다.
- Redis 장애와 consumer 재시작 때 누락된 즉시 회수가 로컬 재인가로 수렴하는지 확인한다.
- reconnect·join 중 권한 회수 경합에서 보호 이벤트를 더 받지 않는지 확인한다.
- 대규모 팀·소켓의 재인가 비용과 허용 회수 지연을 정하고 관측한다.
- 클라이언트의 `channel:access:revoked`·`team:access:revoked` 처리와 재가입 정책을 확인한다.

## 검증

- 멤버십 없는 join·typing·재가입 거부와 미준비 상태의 권한 오회수 방지는 핵심 인가 단위 테스트로 확인한다.
- remote 회수·장애·재연결·동시 메시지는 수동 운영 점검과 지표로 확인한다.
- 주기 재인가의 수렴 시간·비용과 Redis 복구 결과를 기록한다.

## 완료 조건

- 회수·삭제된 범위의 기존 소켓이 모든 replica에서 보호 이벤트를 더 받지 않는다.
- 재연결과 Redis 장애 뒤에도 구독 회수가 수렴하며 허용 지연을 확인할 수 있다.
- projection 미준비만으로 권한 보유 소켓을 room에서 제거하지 않는다.
