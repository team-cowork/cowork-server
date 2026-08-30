# 채널 삭제 시 역할 정책 정리와 재생성 차단

- **서비스**: cowork-channel, cowork-preference, cowork-config, Kafka
- **우선순위**: 🟠 중간
- **현재 상태**: `cowork-channel`은 `channel.event`에 durable `DELETED` full state를 발행하지만 `cowork-preference`에는 authoritative 정책 정리와 queued command 차단이 구현되어 있지 않다
- **파생 원본**: [역할 기반 채널·메시지 읽기 권한 적용](../36-security/role-based-channel-message-read-authorization.md)

## 문제

`ChannelEventPublisher`는 채널 삭제 transaction에서 `channel.event`에 `DELETED` full state를 transactional outbox로
발행한다. 삭제 ledger와 주기 snapshot, completion marker도 유지된다. 그러나 `cowork-preference`의 `KafkaConfig`와
`MainVerticle`에는 `channel.event` consumer가 없고 PostgreSQL에도 채널 수명주기 projection이나 영구 삭제 fence가 없다.

`tb_channel_role_policy_tombstones`는 `role × channel` 정책 키의 삭제 상태만 보존한다. 현재
`ChannelRolePolicyRepository.upsertPolicy`는 같은 키의 tombstone을 제거한 뒤 정책을 다시 만들 수 있으며,
`ChannelRolePolicyCommandProcessor`는 채널 상태를 재검증하지 않고 producer 검증을 신뢰한다. 채널 삭제 전에 접수되어 다른
Kafka topic에 대기하던 `UPSERT`가 삭제 정리 뒤 처리되면 authoritative 정책이 재생성될 수 있다.

삭제 채널은 현재 `cowork-channel`과 `cowork-chat`의 channel projection에서 숨겨지므로 즉시 읽기 우회가 발생한다고
확인되지는 않았다. 다만 authoritative allow state와 command 결과가 삭제된 리소스에 남을 수 있고, snapshot이 그 상태를
재발행할 수 있으므로 삭제 수명주기와 정책 command를 하나의 버전 기반 fence로 직렬화해야 한다.

## 수명주기 계약

| 항목 | 계약 |
|------|------|
| 원천 이벤트 | `channel.event`의 key는 `channelId`이고 `DELETED`를 포함한 full state와 `occurredAt`을 사용한다 |
| 삭제 fence | `cowork-preference`가 채널별 최신 source version과 삭제 상태를 영구 보존하며 stale active state가 삭제를 되돌리지 못한다 |
| 정책 정리 | 채널 삭제 상태, active policy 삭제, policy tombstone, state outbox, consumer checkpoint를 하나의 PostgreSQL transaction으로 적용한다 |
| command 경합 | 삭제 consumer와 command processor가 같은 채널 fence를 잠가 처리 순서를 직렬화한다 |
| 삭제 후 command | 삭제가 먼저 적용되면 queued `UPSERT`와 `DELETE`를 `CHANNEL_DELETED` terminal result로 종료한다 |
| readiness | `channel.event`와 policy state snapshot의 준비 조건을 aggregate별로 분리해 순환 의존 없이 fail-closed한다 |

## 할 일

### 채널 수명주기 projection

- cross-service FK 없이 `channelId`, `teamId`, 삭제 상태, source version을 보존하는 새 PostgreSQL migration을 추가한다.
- `cowork-config`와 `AppConfig`에 `channel.event` topic과 전용 consumer group 설정을 추가한다.
- `channel.event` parser와 Vert.x consumer를 checkpoint, topic UUID, snapshot completion marker, invalid-record latch 규칙에 맞춰 구현한다.
- duplicate·out-of-order·snapshot event에서 높은 version을 우선하고 동일 version 충돌에서는 `DELETED`를 우선한다.
- 삭제 fence를 영구 보존해 오래된 `CREATED`·`UPDATED` event가 채널을 되살리지 못하게 한다.

### 정책 정리와 command fence

- 채널별 active 정책을 잠금 조회하고 모든 역할 정책을 durable tombstone으로 바꾸는 repository 계약을 추가한다.
- `DELETED` 적용, 정책 정리, `preference.channel-role-policy.changed` `DELETE` outbox, checkpoint advance를 하나의 transaction으로 처리한다.
- channel event consumer와 policy command processor가 같은 채널 projection row를 잠그도록 직렬화 경계를 정의한다.
- command가 먼저 처리되면 뒤따른 삭제가 정책을 정리하고, 삭제가 먼저 처리되면 queued command가 `CHANNEL_DELETED`로 끝나게 한다.
- channel projection이 누락되거나 source version이 뒤처진 동안 command 처리를 retry 또는 fail-closed한다.
- event의 `teamId`와 command scope가 일치하지 않으면 정책을 변경하지 않는다.

### 준비 상태와 문서

- `cowork-channel`의 channel snapshot과 `cowork-preference`의 policy snapshot에 aggregate별 readiness gate를 적용해 cold-start 순환 의존을 제거한다.
- `cowork-preference`의 Eureka 등록과 policy command readiness가 필요한 `channel.event` generation과 fresh snapshot을 반영하게 한다.
- `cowork-preference/README.md`와 `docs/development-guide.md`의 Kafka producer·consumer 계약을 갱신한다.

## 검증

- malformed payload, key 불일치, 잘못된 `teamId`·`occurredAt` record가 quarantine과 invalid latch를 거쳐 readiness를 열지 않는지 검증한다.
- out-of-order·duplicate·snapshot event에서 stale active state가 retained delete fence를 지우지 않는지 검증한다.
- `DELETED`가 해당 팀·채널의 모든 active role policy만 tombstone으로 바꾸고 outbox·checkpoint와 원자적으로 반영되는지 검증한다.
- queued `UPSERT` 뒤 `DELETED`, `DELETED` 뒤 queued `UPSERT` 순서 모두 최종 active policy가 남지 않는지 검증한다.
- 삭제 뒤의 queued command가 `CHANNEL_DELETED` result로 종료되고 재시도에도 정책을 재생성하지 않는지 검증한다.
- cleanup 뒤 full snapshot이 과거 allow를 발행하지 않고 `DELETE` tombstone을 재발행하는지 검증한다.
- cold start에서 `channel.event`와 `preference.channel-role-policy.changed`가 순환 대기 없이 각각 fresh snapshot marker 뒤 준비되는지 검증한다.

## 완료 조건

- `cowork-preference` authoritative store에는 삭제된 채널의 active role policy가 남지 않는다.
- 삭제 fence 이후 queued command가 역할 정책을 재생성하지 않는다.
- stale·duplicate event와 snapshot replay 뒤에도 삭제 fence와 policy tombstone이 유지되어 있다.
- 채널 수명주기 적용, 정책 정리, outbox 적재, checkpoint 갱신이 하나의 PostgreSQL transaction에 포함되어 있다.
- 필요한 snapshot readiness가 순환 의존 없이 fail-closed하도록 구성되어 있다.
