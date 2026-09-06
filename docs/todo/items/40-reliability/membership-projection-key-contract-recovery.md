# 멤버십 projection의 compacted 키 계약 복구와 latch 자동 해제

- **서비스**: cowork-chat, cowork-channel, cowork-project, Kafka
- **우선순위**: 🔴 높음
- **현재 상태**: 코드 수정은 작성·정적 검증까지 마쳤고, 커밋·배포와 `channelMember` dataset 1회 rebuild 트리거가 남아 있음
- **관련 작업**: [채팅 projection 증분 재개와 재구축 모드 분리](../31-performance/projection-incremental-resume.md)

## 진행 상태 (2026-09-07)

| 항목 | 코드에서 확인한 상태 | 남은 확인 |
|------|---------------------|-----------|
| legacy 키 수용 | `projection-entity-key.util.ts`의 `matchesCompositeEntityKey`가 `<parentId>`와 `<parentId>:<childId>`를 모두 인정하고, `membership.consumer.ts`·`project-member-event.consumer.ts`가 이를 사용함 | 실제 broker 레코드로 legacy 구간이 통과하는지 확인함 |
| legacy payload 수용 | `isChannelMemberEvent`가 `channelType` 누락을 허용하고 `handleEvent`의 `?? 'TEXT'` 기본값이 도달함 | 오프셋 40697 부근 레코드에 `channelType`이 실제로 없는지 확인함 |
| latch 자동 해제 | `ProjectionReadinessService.quarantine`이 dataset을 `REBUILD_REQUIRED`로 바꾸지 않고 partition도 pause하지 않음 | 서로 다른 두 full snapshot이 latch를 해제하는 경로를 운영에서 관측함 |
| replay 소켓 억제 | `isStreamLive`가 추가되어 `membership`·`channel-event`·`team-member-event` consumer의 소켓 부작용을 gate함 | rebalance로 readiness가 잠깐 닫히는 구간의 이벤트 유실 폭을 관측함 |
| 정적 검증 | `tsc --noEmit`·`eslint` 통과, 기존 단위 테스트 88개 통과 | 배포 후 dataset이 `ACTIVE`에 도달하는지 확인함 |

## 문제

`cowork-chat`의 `channel.member.event` projection이 `REBUILD_REQUIRED` 상태에서 벗어나지 못한다. `POST /admin/projections/{stream}/rebuild`로 rebuild를 실행해도 즉시 같은 상태로 되돌아간다.

`ChannelMemberEventPublisher`의 메시지 키는 `b927dce2`(2026-05-09)부터 `event.channelId.toString()`이었고 `e03d5113`(2026-08-27)에서 `"${channelId}:${userId}"`로 바뀌었다. `channel.member.event`는 `docker-compose.yml`의 `KAFKA_COMPACTED_TOPICS`에 포함되어 log compaction이 적용된다. compaction은 키별 최신 레코드를 보존하므로 `1`과 `1:8`은 서로 다른 키 공간으로 남고, legacy 키 레코드는 시간이 지나도 만료되지 않는다. `requestRebuild`는 rebuild base offset을 broker low watermark로 잡고 `.claude/rules/kafka-projections.md`도 earliest부터의 재생을 요구하므로, rebuild는 매번 legacy 키 구간을 다시 읽는다. `membership.consumer.ts`의 키 검사가 `ProjectionContractError`를 던지면 `applyProjectionMessage`가 이를 quarantine으로 넘긴다.

`ProjectionReadinessService.quarantine`은 quarantine 뒤 `markStreamUnrecoverable`을 무조건 호출해 dataset을 `REBUILD_REQUIRED`로 바꾸고 assigned partition을 pause했다. 한편 `projection-checkpoint.repository.ts`의 `observeProjectionRecoverySnapshot`은 gap 이후 서로 다른 두 full snapshot이 invalid-record latch를 해제하는 복구 경로를 이미 구현하고 있고, `.claude/rules/kafka-projections.md`도 이 경로를 명시적으로 허용한다. partition이 pause되면 snapshot barrier가 더 도착하지 않으므로 그 복구 경로는 도달하지 못했다. 즉 legacy 키는 방아쇠이고, 교착 자체는 두 복구 설계가 서로를 무력화한 결과다.

payload 계약도 함께 어긋나 있었다. `channelType`은 `8e0d97bb`(2026-06-12)에 추가된 필드인데 `isChannelMemberEvent`가 이를 필수로 요구해, `handleEvent`의 `event.channelType ?? 'TEXT'` 기본값이 도달할 수 없는 코드였다. `ProjectMemberEventPublisher`도 `7e7b203b`(2026-08-27)에서 동일한 키 포맷 변경을 했고 `project.member.event` 역시 compacted topic이므로 같은 결함을 잠재적으로 갖는다. `team.member.event`와 `preference.*` 스트림은 키 포맷이 바뀐 적이 없어 해당하지 않는다.

## 레코드 계약 변화

| 시기 | 메시지 키 | `channelType` | 근거 커밋 |
|------|-----------|---------------|-----------|
| 2026-05-09 ~ 2026-06-12 | `<channelId>` | 없음 | `b927dce2` |
| 2026-06-12 ~ 2026-08-27 | `<channelId>` | 있음 | `8e0d97bb` |
| 2026-08-27 이후 | `<channelId>:<userId>` | 있음 | `e03d5113` |

compaction 대상이므로 위 세 구간의 레코드가 키 공간별로 공존하며, 어느 구간도 자연 만료되지 않는다.

## 할 일

### 계약 수용

- compacted topic의 메시지 키를 영구 계약으로 다루고, legacy 키와 현재 키를 함께 인정하는 판정을 공용 helper로 분리한다.
- `channel.member.event`와 `project.member.event` consumer가 그 helper를 사용하고, 불일치 시 기대 키를 로그에 남긴다.
- `channelType` 누락을 payload 검증에서 허용하고 기존 기본값이 실제로 적용되게 한다.

### latch 복구 경로

- quarantine이 dataset을 `REBUILD_REQUIRED`로 승격하거나 partition을 pause하지 않게 하고, invalid-record latch와 readiness 차단만 유지한다.
- 재시작 시 `ACTIVE` dataset의 latch를 rebuild 사유로 변환하지 않고 그대로 둔다.

### replay 중 소켓 부작용 차단

- stream 단위로 live 여부를 판정하는 접근자를 readiness service에 추가한다.
- `membership`·`channel-event`·`team-member-event` consumer의 소켓 emit, `socketsLeave`, 접근 취소 평가를 live 상태에서만 수행한다.

### 운영 전환

- 배포 후 `channelMember` dataset에 rebuild를 1회 트리거해 `REBUILD_REQUIRED`에서 빠져나오게 한다.
- `project.member.event` projection의 dataset 상태를 함께 점검한다.

## 검증

- `cowork-chat`에서 `tsc --noEmit`과 `eslint`가 통과한다.
- 기존 단위 테스트가 통과한다. `.claude/rules/kafka-projections.md`의 시험 범위 규칙에 따라 quarantine·recovery 기전을 고정하는 테스트는 추가하지 않는다.
- 오프셋 40697 부근 레코드를 덤프해 키 포맷과 `channelType` 유무를 확인한다. 이 구간은 보고된 값이며 아직 broker에서 직접 확인하지 않았다.
- rebuild 트리거 뒤 dataset이 `REBUILDING`을 거쳐 `ACTIVE`에 도달하는지 `GET /admin/projections`로 확인한다.

## 완료 조건

- legacy 키와 현재 키 레코드가 모두 계약 위반 없이 처리된다.
- earliest부터의 재생이 legacy 구간에서 중단되지 않는다.
- 계약 위반 레코드는 격리되고 readiness는 닫히지만 dataset은 `REBUILD_REQUIRED`로 바뀌지 않는다.
- 격리 뒤 서로 다른 두 full snapshot이 도착하면 운영자 개입 없이 latch가 해제된다.
- rebuild와 bootstrap 재생 중에는 소켓 이벤트와 접근 취소가 발생하지 않는다.
- `channelMember`와 `projectMember` dataset이 `ACTIVE` 상태다.
