# 상태 토픽 키 포맷 변경에 따른 토픽 버전 분리

- **서비스**: cowork-channel, cowork-project, cowork-chat, cowork-voice, cowork-config, Kafka
- **우선순위**: 🟠 중간
- **현재 상태**: 네 개 compacted 토픽이 은퇴한 키 포맷 레코드를 영구 보존하고 있고, 소비자는 하위호환 파싱으로 이를 수용하는 임시 상태임
- **관련 작업**: [채팅 projection 증분 재개와 재구축 모드 분리](../31-performance/projection-incremental-resume.md)

## 문제

`docker-compose.yml`의 `KAFKA_COMPACTED_TOPICS`에 속한 상태 토픽 네 개가 키 포맷을 바꾼 적이 있다. compaction은 키별 최신 레코드를 무기한 보존하므로 은퇴한 포맷으로 쓰인 레코드는 만료되지 않고, `.claude/rules/kafka-projections.md`가 요구하는 earliest부터의 재생에 영구히 나타난다.

`channel.member.event`는 `e03d5113`(2026-08-27) 이전까지 `<channelId>` 단독 키였고, `project.member.event`도 `7e7b203b`에서 같은 변경을 했다. 현재 포맷은 `<parentId>:<childId>`이며 은퇴 포맷과 키 공간이 완전히 분리되어 있어 어느 레코드도 덮이지 않는다.

`channel.event`는 두 번 바뀌었다. `9a51d22a`(2026-06-04)에서 `<teamId>`, `8e0d97bb`(2026-06-12)에서 팀 채널은 `<teamId>`·DM 채널은 `dm-<channelId>`, `e03d5113`(2026-08-27)에서 `<channelId>`가 되었다. `<teamId>` 키는 숫자라 현재 `<channelId>` 키 공간과 겹치므로 같은 숫자의 channelId가 이후 발행했다면 덮이지만, 그렇지 않은 teamId의 레코드는 남는다. `dm-<channelId>` 키는 비숫자라 별도 키 공간으로 영구 잔존한다. `project.event`도 `7e7b203b`에서 `<teamId>`에서 `<projectId>`로 바뀌어 같은 숫자 충돌 성질을 갖는다.

소비자는 토픽마다 다르다. `channel.member.event`는 `cowork-chat`과 `cowork-voice`가, `channel.event`는 `cowork-chat`과 `cowork-project`가 읽는다. 각 소비자가 키를 payload의 식별자와 직접 비교하므로, 은퇴 키 레코드는 계약 위반으로 격리되고 readiness가 닫힌다. 현재는 은퇴 포맷을 함께 인정하는 하위호환 파싱을 넣어 재생이 통과하지만, 이는 컷오버까지의 임시 조치이며 원인을 제거하지 않는다.

## 대상 토픽

| 토픽                   | 은퇴 키 포맷                 | 현재 키                | 소비자                      |
|------------------------|------------------------------|------------------------|-----------------------------|
| `channel.member.event` | `<channelId>`                | `<channelId>:<userId>` | cowork-chat, cowork-voice   |
| `project.member.event` | `<projectId>`                | `<projectId>:<userId>` | cowork-chat                 |
| `channel.event`        | `<teamId>`, `dm-<channelId>` | `<channelId>`          | cowork-chat, cowork-project |
| `project.event`        | `<teamId>`                   | `<projectId>`          | cowork-chat                 |

키 포맷이 바뀐 적이 없어 대상이 아닌 토픽은 `team.member.event`, `team.lifecycle`, `project.github-repo.event`, `preference.*` 네 개, `user.profile.event`다.

새 토픽 이름 규칙은 아직 정하지 않았다. `<topic>.v2` 형태를 후보로 둔다.

## 참조 지점

| 구분              | 위치                                                                                                                      |
|-------------------|---------------------------------------------------------------------------------------------------------------------------|
| 토픽 provisioning | `docker-compose.yml`의 `KAFKA_TOPICS`, `KAFKA_COMPACTED_TOPICS`                                                           |
| channel 발행      | `ChannelEventPublisher`·`ChannelMemberEventPublisher`의 `TOPIC`, `ChannelProjectionSnapshotPublisher`의 `SNAPSHOT_TOPICS` |
| project 발행      | `ProjectEventPublisher`·`ProjectMemberEventPublisher`의 `TOPIC`, `ProjectProjectionSnapshotPublisher`의 `SNAPSHOT_TOPICS` |
| chat 소비         | `projection-readiness.service.ts`의 `PROJECTION_STREAMS` 네 항목                                                          |
| project 소비      | `ProjectionTopics`·`ChannelStateConsumer`                                                                                 |
| voice 소비        | `cowork-config`의 `cowork-voice-local.yml`·`cowork-voice-prod.yml`의 `KAFKA_TOPIC_CHANNEL_MEMBER_EVENT`                   |
| 하위호환 파싱     | `cowork-chat/src/common/kafka/projection-entity-key.util.ts`, `cowork-project/.../projection/ProjectionEntityKey.kt`      |

## 컷오버 성질

`ProjectionReadinessService.resolveStartupDataset`은 저장된 dataset의 `topic`이 설정과 다르면 `Projection dataset stream identity changed` 사유로 `REBUILD_REQUIRED`로 전환한다. 따라서 토픽 상수만 바꿔도 rebuild 필요 상태가 잡히며 `sourceGeneration`을 따로 조작할 필요가 없다. `REBUILD_REQUIRED`는 관리자 트리거를 기다리므로 `POST /admin/projections/{stream}/rebuild` 호출은 여전히 필요하다.

새 토픽은 비어 있는 상태로 시작하지만 `hasCompletedSnapshotBarriers`가 파티션별 완료 marker를 요구하므로, 주기 snapshot 재발행(각 publisher `REPUBLISH_INTERVAL_MS` 300초)이 전량을 채우고 marker를 남길 때까지 readiness가 열리지 않는다. 이 성질 덕분에 이중 발행 없이 전환할 수 있으며, 전환 직후 최대 한 사이클 동안 readiness가 닫혀 있는 것은 정상 동작이다.

## 할 일

### 토픽 신설

- 네 토픽의 새 이름 규칙을 확정하고 `docker-compose.yml`의 일반 토픽 목록과 compaction 목록에 추가한다.
- 운영 클러스터에도 같은 파티션 수·복제 계수·`cleanup.policy=compact`로 생성한다.

### 발행 전환

- `ChannelEventPublisher`·`ChannelMemberEventPublisher`·`ProjectEventPublisher`·`ProjectMemberEventPublisher`의 `TOPIC` 상수를 새 토픽으로 바꾼다.
- 두 snapshot publisher의 `SNAPSHOT_TOPICS`를 새 토픽으로 바꿔 완료 marker가 같은 토픽에 실리게 한다.

### 소비 전환

- `cowork-chat`의 `PROJECTION_STREAMS` 네 항목 topic을 새 토픽으로 바꾼다.
- `cowork-project`의 `ProjectionTopics`에서 `channel.event`를 새 토픽으로 바꾼다.
- `cowork-config`의 voice 설정에서 `KAFKA_TOPIC_CHANNEL_MEMBER_EVENT`를 새 토픽으로 바꾸고 consumer group도 함께 분리한다.
- 전환 뒤 각 stream에 rebuild를 트리거해 새 토픽 earliest부터 재구축한다.

### 하위호환 코드 제거

- `cowork-chat/src/common/kafka/projection-entity-key.util.ts`를 삭제하고 네 consumer의 키 검사를 현재 포맷 단일 비교로 되돌린다.
- `cowork-project/.../projection/ProjectionEntityKey.kt`를 삭제하고 `ChannelStateConsumer`의 키 검사를 `record.key() != payload.channelId.toString()`으로 되돌린다.
- `ChannelStatePayload.teamId`를 제거한다. 은퇴 키 검증 용도로만 추가한 필드다.
- `membership.consumer.ts`의 `channelType` 선택적 허용과 `ChannelMemberEvent.channelType?`를 필수로 되돌린다.
- 코드의 `TODO(topic-versioning)` 주석을 모두 제거한다.

### 구 토픽 폐기

- 모든 소비자가 새 토픽에서 `ACTIVE`를 유지하는 것을 확인한 뒤 구 토픽을 폐기한다.
- 폐기한 이름은 재사용하지 않는다.

## 검증

- `cowork-chat`에서 `tsc --noEmit`·`eslint`·기존 단위 테스트가 통과한다. `cowork-project`는 `mvnw compile`, `cowork-team`은 `ktlintCheck`와 `compileKotlin`이 통과한다.
- `.claude/rules/kafka-projections.md`의 시험 범위 규칙에 따라 토픽 전환·compaction·barrier 기전을 고정하는 테스트는 추가하지 않는다.
- 전환 뒤 새 토픽 네 개를 earliest부터 스캔해 은퇴 포맷 키가 없는지 확인한다.
- `GET /admin/projections`로 `channel`·`channelMember`·`project`·`projectMember`가 `ACTIVE`에 도달하는지 확인한다.
- `cowork-project`와 `cowork-voice`가 새 토픽에서 각각 채널 상태와 멤버십 조회를 정상 응답하는지 확인한다.
- 하위호환 코드를 제거한 뒤 rebuild를 1회 실행해 재생이 끝까지 진행되는지 확인한다.

## 완료 조건

- 네 상태 토픽의 키 공간이 현재 포맷만으로 이루어져 있다.
- 모든 소비자가 새 토픽을 읽고 하위호환 분기 없이 동작한다.
- earliest부터의 재생이 계약 위반 없이 완료된다.
- 구 토픽이 폐기되어 있고 그 이름이 코드·설정·compose 어디에도 남아 있지 않다.
- 코드에 `TODO(topic-versioning)` 주석이 남아 있지 않다.
