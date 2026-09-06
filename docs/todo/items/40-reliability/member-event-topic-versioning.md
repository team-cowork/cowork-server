# 멤버십 상태 토픽의 키 포맷 변경에 따른 토픽 버전 분리

- **서비스**: cowork-channel, cowork-project, cowork-chat, cowork-voice, cowork-config, Kafka
- **우선순위**: 🟠 중간
- **현재 상태**: `cowork-chat`은 하위호환 파싱으로 legacy 키를 통과시키지만 `cowork-voice`는 그대로 막혀 있고, 두 토픽의 키 공간은 legacy 포맷을 영구히 보존하고 있음
- **관련 작업**: [채팅 projection 증분 재개와 재구축 모드 분리](../31-performance/projection-incremental-resume.md)

## 문제

`channel.member.event`의 메시지 키는 `b927dce2`(2026-05-09)부터 `event.channelId.toString()`이었고 `e03d5113`(2026-08-27)에서 `"${channelId}:${userId}"`로 바뀌었다. `project.member.event`도 `7e7b203b`에서 같은 변경을 했다. 두 토픽 모두 `docker-compose.yml`의 `KAFKA_COMPACTED_TOPICS`에 있어 log compaction이 적용된다.

compaction은 키별 최신 레코드를 무기한 보존한다. `1`과 `1:8`은 서로 다른 키이므로 legacy 포맷으로 쓰인 레코드는 만료되지 않고, `.claude/rules/kafka-projections.md`가 요구하는 earliest부터의 재생은 매번 그 구간을 다시 읽는다. 같은 규칙 파일이 컴팩션 토픽의 키를 영구 스키마로 규정하고 breaking 변경 시 토픽 버전 분리를 요구하도록 개정되었다.

소비 측 상태는 서비스마다 다르다. `cowork-chat`은 `projection-entity-key.util.ts`의 `matchesCompositeEntityKey`가 두 포맷을 인정하고 `isChannelMemberEvent`가 `channelType` 누락을 허용해 재생이 통과한다. `cowork-voice`는 `internal/infra/channel/projection.go`에서 `fmt.Sprintf("%d:%d", ...)`와 키를 직접 비교하고 `channelType`이 비면 `unsupported channelType`으로 거부하며, `internal/infra/channel/consumer.go`가 `segkafka.FirstOffset`으로 읽으므로 legacy 구간에서 동일하게 막힌다.

하위호환 파싱은 재생을 되살렸을 뿐 원인을 제거하지 않는다. legacy 키가 남아 있는 한 두 소비자 모두 그 분기를 영구히 유지해야 하고, 토픽 위의 키 계약은 깨진 채로 남는다.

## 대상 토픽과 참조 지점

| 구분 | 위치 |
|------|------|
| 토픽 provisioning | `docker-compose.yml`의 `KAFKA_TOPICS`, `KAFKA_COMPACTED_TOPICS` |
| channel 발행 | `ChannelMemberEventPublisher`의 `TOPIC`, `ChannelProjectionSnapshotPublisher`의 `SNAPSHOT_TOPICS` |
| project 발행 | `ProjectMemberEventPublisher`의 `TOPIC`, `ProjectProjectionSnapshotPublisher`의 `SNAPSHOT_TOPICS` |
| chat 소비 | `projection-readiness.service.ts`의 `PROJECTION_STREAMS.channelMember`, `PROJECTION_STREAMS.projectMember` |
| voice 소비 | `cowork-config`의 `cowork-voice-local.yml`·`cowork-voice-prod.yml`의 `KAFKA_TOPIC_CHANNEL_MEMBER_EVENT` |

새 토픽 이름 규칙은 아직 정하지 않았다. `channel.member.event.v2` 형태를 후보로 둔다.

## 컷오버 성질

`ProjectionReadinessService.resolveStartupDataset`은 저장된 dataset의 `topic`이 설정과 다르면 `Projection dataset stream identity changed` 사유로 `REBUILD_REQUIRED`로 전환한다. 따라서 토픽 상수만 바꿔도 rebuild 필요 상태는 자동으로 잡히며, `sourceGeneration`을 따로 조작할 필요가 없다. 다만 `REBUILD_REQUIRED`는 관리자 트리거를 기다리므로 `POST /admin/projections/{stream}/rebuild` 호출은 여전히 필요하다.

새 토픽은 비어 있는 상태로 시작하지만 `hasCompletedSnapshotBarriers`가 파티션별 완료 marker를 요구하므로, 주기 snapshot 재발행(각 publisher `REPUBLISH_INTERVAL_MS` 300초)이 전량을 채우고 marker를 남길 때까지 readiness가 열리지 않는다. 이 성질 덕분에 이중 발행 없이 전환할 수 있다. 전환 직후 최대 한 사이클 동안 readiness가 닫혀 있는 것은 정상 동작이다.

## 할 일

### 토픽 신설

- 새 이름 규칙을 확정하고 `docker-compose.yml`의 일반 토픽 목록과 compaction 목록에 두 토픽을 추가한다.
- 운영 클러스터에도 같은 파티션 수·복제 계수·`cleanup.policy=compact`로 생성한다.

### 발행 전환

- `ChannelMemberEventPublisher`와 `ProjectMemberEventPublisher`의 `TOPIC` 상수를 새 토픽으로 바꾼다.
- 두 snapshot publisher의 `SNAPSHOT_TOPICS`를 새 토픽으로 바꿔 완료 marker가 같은 토픽에 실리게 한다.

### 소비 전환

- `PROJECTION_STREAMS`의 `channelMember`·`projectMember` topic을 새 토픽으로 바꾼다.
- `cowork-config`의 voice 설정에서 `KAFKA_TOPIC_CHANNEL_MEMBER_EVENT`를 새 토픽으로 바꾸고 consumer group도 함께 분리한다.
- 전환 뒤 각 stream에 rebuild를 트리거해 새 토픽 earliest부터 재구축한다.

### 하위호환 코드 제거

- 새 토픽 기준으로 재구축이 완료된 뒤 `matchesCompositeEntityKey`의 legacy 분기를 제거한다.
- `isChannelMemberEvent`의 `channelType` 선택적 허용을 되돌린다. 해당 필드가 없던 레코드는 모두 legacy 토픽에만 존재한다.
- `cowork-voice`는 새 토픽에서 현재 포맷만 읽으므로 별도 하위호환 분기를 추가하지 않는다.

### 구 토픽 폐기

- 두 소비자가 새 토픽에서 `ACTIVE`를 유지하는 것을 확인한 뒤 구 토픽을 폐기한다.
- 폐기한 이름은 재사용하지 않는다.

## 검증

- `cowork-chat`에서 `tsc --noEmit`과 `eslint`가 통과하고 기존 단위 테스트가 통과한다. `cowork-voice`는 기존 Go 테스트가 통과한다.
- `.claude/rules/kafka-projections.md`의 시험 범위 규칙에 따라 토픽 전환·컴팩션·barrier 기전을 고정하는 테스트는 추가하지 않는다.
- 전환 뒤 새 토픽을 earliest부터 스캔해 `:` 없는 entity 키가 없는지 확인한다.
- `GET /admin/projections`로 `channelMember`·`projectMember`가 `REBUILDING`을 거쳐 `ACTIVE`에 도달하는지 확인한다.
- `cowork-voice`가 새 토픽에서 멤버십 조회를 정상 응답하는지 확인한다.
- 하위호환 코드를 제거한 뒤 rebuild를 1회 실행해 재생이 끝까지 진행되는지 확인한다.

## 완료 조건

- 두 멤버십 상태 토픽의 키 공간이 `<parentId>:<childId>` 포맷만으로 이루어져 있다.
- `cowork-chat`과 `cowork-voice`가 같은 새 토픽을 읽고 두 곳 모두 하위호환 분기 없이 동작한다.
- earliest부터의 재생이 계약 위반 없이 완료된다.
- 구 토픽이 폐기되어 있고 그 이름이 어디에도 남아 있지 않다.
