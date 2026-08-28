# 멤버십 회수 시 WebSocket 구독 강제 해제

- **서비스**: cowork-chat
- **우선순위**: 🔴 높음
- **현재 상태**: 연결 중 확인한 멤버십이 회수되어도 기존 소켓은 보호된 Socket.IO room에 계속 남아 있음
- **관련 작업**: [Socket.IO Redis adapter 준비 상태와 복구 보장](../29-reliability/socketio-redis-adapter-readiness.md)

## 문제

`cowork-chat/src/chat/chat.gateway.ts`의 `ChatGateway.handleJoin`과 `ChatGateway.handleJoinTeam`은 각각 `join`, `join:team` 요청 시점에 멤버십을 확인한 뒤 소켓을 `chat:{channelId}` 또는 `team:{teamId}` room에 가입시킨다. 이후 브로드캐스트 시점에는 room 구성원을 다시 인가하지 않으므로 이 가입 상태가 실시간 이벤트 수신 권한으로 사용된다.

`MembershipConsumer.handleEvent`는 `channel.member.event`의 `LEAVE`를 MongoDB tombstone으로 반영하고 `member:left`를 브로드캐스트하지만, 회수 대상 사용자의 기존 소켓을 `chat:{channelId}`에서 제거하지 않는다. `TeamMemberEventConsumer.handleEvent`도 `team.member.event`의 `DELETE` projection만 갱신하며 Socket.IO 서버를 사용하지 않는다. `ChannelEventConsumer.handleEvent`의 `DELETED` 역시 삭제 알림만 보내고 해당 채널 room을 비우지 않는다.

따라서 연결을 유지한 사용자는 탈퇴·강제 제거·채널 삭제 이후에도 room에 남아 새 메시지와 멤버십·채널 이벤트를 받을 수 있다. `ChatGateway.relayTyping`도 현재 room 가입 여부만 확인하므로 멤버십이 회수된 소켓이 타이핑 이벤트를 계속 보낼 수 있다.

## 회수 이벤트별 처리 범위

| 원본 이벤트 | projection 반영 후 강제 조치 | 후속 알림 |
|-------------|------------------------------|-----------|
| `channel.member.event`의 `LEAVE` | 해당 사용자의 모든 소켓을 `chat:{channelId}`에서 제거함 | 남은 채널 멤버에게 `member:left`를 전송함 |
| `team.member.event`의 `DELETE` | 해당 사용자의 소켓을 `team:{teamId}`와 팀 소속 채널 room에서 제거함 | 회수 대상에게 직접 권한 변경 이벤트를 전송할지 계약을 정함 |
| `channel.event`의 `DELETED` | 모든 소켓을 `chat:{channelId}`에서 제거함 | 팀 room에 `channel:deleted`를 전송함 |
| `project.member.event`의 `REMOVED` | 프로젝트 채널 멤버십 회수 이벤트와의 순서·책임을 확정함 | 중복 해제는 멱등하게 처리함 |

projection 저장 성공 후 room 해제를 수행하고, 해제가 완료된 뒤 보호 이벤트가 더 전달되지 않도록 처리 순서를 고정한다. 동일 이벤트 재처리와 이미 연결이 끊긴 소켓에 대한 해제는 오류 없이 멱등하게 끝나야 한다.

## 할 일

### 강제 구독 해제

- `ChatGateway.handleConnection`에서 가입하는 `user:{userId}` room을 회수 대상 소켓 선택에 활용한다.
- `MembershipConsumer`가 `LEAVE` 적용 성공 후 대상 사용자의 모든 소켓에서 `chat:{channelId}` room을 제거하도록 구현한다.
- `TeamMemberEventConsumer`와 `ChannelEventConsumer`에 필요한 Socket.IO 협력 경계를 추가하고 팀 제거 및 채널 삭제를 처리한다.
- 팀 제거 시 해제할 채널 목록을 projection에서 일괄 조회하고 채널마다 사용자별 소켓을 순회하지 않도록 설계한다.
- room 해제 실패를 로그만 남기고 끝내지 않고 재시도 또는 연결 종료로 수렴시키는 정책을 적용한다.
- 회수 대상 소켓이 권한 변경을 인지할 수 있도록 사용자 전용 room에 안정적인 이벤트 이름과 payload를 정의한다.

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
