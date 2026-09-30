# projection 미준비 중 권한 보유 소켓의 room 오회수 차단

- **서비스**: cowork-chat
- **우선순위**: 🟠 중간
- **현재 상태**: 회수 이벤트 처리가 전체 projection readiness를 확인하지 않고 권한을 판정해, 다른 stream이 따라잡는 동안 권한이 있는 소켓까지 room에서 제거함
- **파생 원본**: [멤버십 회수 시 WebSocket 구독 강제 해제](../25-security/websocket-membership-revocation.md)

## 문제

`ChannelMessageReadAccessService.evaluate`는 `ProjectionReadinessService.isReady()`가 false이면 모든 요청을 거부로 반환한다. `isReady()`는 모든 projection stream이 준비되었을 때만 true이고, `isStreamLive(name)`은 해당 stream 하나의 준비 상태만 반영한다. stream별 readiness는 consumer `CRASH`, rebalance, lease 갱신 실패, lag로 각각 닫힌다.

`evictUnauthorizedSockets`는 readiness를 확인하지 않고 이 판정으로 소켓을 room에서 제거한 뒤 `channel:access:revoked`를 보낸다. `ChannelEventConsumer`와 `TeamMemberEventConsumer`는 자기 stream의 `isStreamLive`만 확인하므로, 다른 stream이 닫혀 있으면 전부 거부된 판정으로 소켓을 제거한다. `ChannelEventConsumer`의 `UPDATED` 처리는 사용자 필터 없이 채널 room의 모든 소켓을 대상으로 한다. `ChannelRolePolicyEventConsumer`와 `TeamRoleEventConsumer`는 `isStreamLive`도 확인하지 않아 재처리와 rebuild replay 중에도 레코드마다 회수를 실행한다. `MembershipConsumer`의 `LEAVE` 처리도 같은 판정을 쓰는 `isMember`로 소켓을 제거하므로, 전체 readiness가 닫혀 있으면 적용되지 않은 과거 `LEAVE`도 다시 가입한 사용자를 제거한다.

전송 시점 인가가 있어 보호 이벤트가 노출되지는 않는다. 대신 권한이 있는 사용자가 room에서 빠져 실시간 이벤트를 받지 못하고, readiness가 닫힌 동안에는 packet middleware가 다시 가입하는 요청도 거부한다.

## 할 일

- `evictUnauthorizedSockets`에 `evictUnauthorizedRooms`와 같은 판정 전후 readiness 확인을 적용한다.
- `MembershipConsumer`의 `LEAVE` 회수 판정에도 같은 조건을 적용한다.
- `ChannelRolePolicyEventConsumer`·`TeamRoleEventConsumer`에 live 조건이 없는 이유를 확인하고 다른 consumer와 조건을 맞춘다.
- readiness가 닫힌 동안 건너뛴 회수를 `ChatGateway`의 30초 로컬 room 재검증이 정리하는지 확인한다.

## 검증

- 전체 readiness가 닫힌 동안 회수 경로가 소켓을 제거하지 않는 판단을 access service 단위 테스트로 검증한다.

## 완료 조건

- 전체 projection readiness가 닫혀 있는 동안 회수 이벤트 처리는 권한을 보유한 소켓을 room에서 제거하지 않는다.
- readiness가 닫힌 동안 건너뛴 회수는 readiness가 다시 열린 뒤 room 재검증으로 정리되어 있다.
