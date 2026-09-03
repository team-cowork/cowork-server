# 멤버십 회수 시 WebSocket 구독 강제 해제

- **서비스**: cowork-chat
- **우선순위**: 🔴 높음
- **현재 상태**: 채널·팀 멤버십 회수와 채널 삭제의 room 해제, 타이핑 재인가가 구현되어 있으며 다중 replica 전달·Redis 장애·재연결 경합 검증이 남아 있음
- **관련 작업**: [Socket.IO Redis adapter 준비 상태와 복구 보장](../29-reliability/socketio-redis-adapter-readiness.md)

## 진행 상태 (2026-09-03)

| 경로 | 코드에서 확인한 상태 |
|------|---------------------|
| `MembershipConsumer` | `LEAVE` projection 뒤 현재 읽기 권한이 없으면 `user:{userId}` 소켓의 `chat:{channelId}` room을 제거함 |
| `TeamMemberEventConsumer` | 팀 채널의 읽기 권한을 다시 평가해 소켓을 제거하고, 유효한 팀 삭제 상태이면 팀 room도 제거함 |
| `ChannelEventConsumer` | 삭제 이벤트와 삭제 snapshot 적용 뒤 채널 room을 비움 |
| `ChatGateway.relayTyping` | room 가입 여부와 현재 읽기 권한을 다시 확인하고 거부된 소켓을 room에서 제거함 |
| 남은 범위 | 실제 여러 replica의 remote room 해제, adapter 장애 중 회수 보장, reconnect·동시 발행 경합을 이번 문서 점검에서 검증하지 않음 |

## 문제

최초 점검 당시 `ChatGateway.handleJoin`과 `ChatGateway.handleJoinTeam`에서 검사한 가입 상태만 실시간 이벤트 수신에 사용하고, 탈퇴 projection 반영 뒤 기존 room을 회수하지 않는 문제가 있었다. 이후 역할 기반 읽기 권한 작업에서 회수와 브로드캐스트 재인가 경로가 추가되었으므로 이 문제가 모든 경로에 그대로 남아 있다고 표현하지 않는다.

현재 `MembershipConsumer`는 채널 탈퇴 후 `socketsLeave`를 호출하고, `TeamMemberEventConsumer`는 `ChannelMessageReadAccessService.evictUnauthorizedSockets`로 팀 채널의 접근을 다시 평가한다. `ChannelEventConsumer`는 삭제된 채널 room을 비우고, `ChatGateway.relayTyping`도 현재 인가를 재확인한다. 사용자에게는 `channel:access:revoked` 또는 `team:access:revoked` 이벤트를 보낸다.

다만 `RedisIoAdapter`의 초기 실패 시 in-memory fallback과 adapter 준비 상태를 반영하지 않는 readiness 문제는 남아 있다. room 회수 코드가 존재한다는 사실만으로 다른 replica의 소켓까지 회수가 완료되었다고 판정할 수 없다. 이 항목은 해당 장애 경계와 재연결·동시 발행 시나리오까지 검증한 뒤 완료한다.

## 회수 이벤트별 처리 범위

| 원본 이벤트 | projection 반영 후 강제 조치 | 후속 알림 |
|-------------|------------------------------|-----------|
| `channel.member.event`의 `LEAVE` | 해당 사용자의 모든 소켓을 `chat:{channelId}`에서 제거함 | 남은 채널 멤버에게 `member:left`를 전송함 |
| `team.member.event`의 `DELETE` | 해당 사용자의 소켓을 `team:{teamId}`와 팀 소속 채널 room에서 제거함 | 회수 대상에게 직접 권한 변경 이벤트를 전송할지 계약을 정함 |
| `channel.event`의 `DELETED` | 모든 소켓을 `chat:{channelId}`에서 제거함 | 팀 room에 `channel:deleted`를 전송함 |
| `project.member.event`의 `REMOVED` | 프로젝트 채널 멤버십 회수 이벤트와의 순서·책임을 확정함 | 중복 해제는 멱등하게 처리함 |

projection 저장 성공 후 room 해제를 수행하고, 해제가 완료된 뒤 보호 이벤트가 더 전달되지 않도록 처리 순서를 고정한다. 동일 이벤트 재처리와 이미 연결이 끊긴 소켓에 대한 해제는 오류 없이 멱등하게 끝나야 한다.

## 할 일

### 구현된 회수 경로의 검증과 보완

- `user:{userId}` room 기반 선택이 동일 사용자의 여러 소켓과 remote replica까지 포함하는지 검증한다.
- `MembershipConsumer`, `TeamMemberEventConsumer`, `ChannelEventConsumer`의 현재 회수 동작을 duplicate·stale event·snapshot 조건별로 검증한다.
- 팀 채널의 일괄 권한 평가와 소켓 조회가 대규모 팀에서도 허용 가능한 비용인지 확인한다.
- room 해제 실패를 로그만 남기고 끝내지 않고 재시도 또는 연결 종료로 수렴시키는 정책을 적용한다.
- 이미 구현된 `channel:access:revoked`·`team:access:revoked`의 payload와 클라이언트 재가입 동작을 계약으로 고정한다.

### 다중 replica 보장

- Redis adapter를 통해 다른 `cowork-chat` replica에 연결된 동일 사용자의 소켓에도 room 해제가 전달되게 한다.
- Redis adapter가 준비되지 않은 다중 replica 상태에서는 회수 완료로 오판하지 않도록 readiness와 연동한다.
- 이벤트 재처리, Socket.IO reconnect, connection state recovery가 회수된 room 가입을 복원하지 않는지 확인한다.

## 검증

- 한 사용자가 여러 브라우저와 여러 replica에 연결된 상태에서 `LEAVE`가 적용되면 모든 소켓이 해당 채널 room에서 빠지는지 검증한다.
- `DELETE`로 팀 멤버십을 회수한 뒤 기존 소켓이 팀 및 팀 소속 채널 이벤트를 받거나 타이핑 이벤트를 전송하지 못하는지 검증한다.
- 채널 삭제 뒤 `chat:{channelId}` room이 비워지고 재가입도 거부되는지 검증한다.
- 중복 `LEAVE`·`DELETE` 이벤트와 이미 해제된 room에 대한 처리가 예외 없이 완료되는지 검증한다.
- 회수 처리와 동시에 메시지가 발행되는 경합 테스트에서 projection 반영 이후의 메시지가 회수 대상에게 전달되지 않는지 검증한다.

## 완료 조건

- 채널 멤버십이 회수된 사용자의 기존 모든 소켓은 해당 채널 room에 남아 있지 않는다.
- 팀 멤버십이 회수된 사용자는 기존 연결로 팀 및 팀 소속 채널의 보호 이벤트를 받지 않는다.
- 삭제된 채널의 room에는 기존 소켓이 남아 있지 않는다.
- 다중 replica와 reconnect 환경에서도 구독 회수가 동일하게 적용되어 있다.
- 멤버십 회수 후 타이핑을 포함한 클라이언트 발신 이벤트가 해당 채널에 전달되지 않는다.
