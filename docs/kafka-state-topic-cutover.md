# 상태 토픽 v2 운영 전환

저장소의 v2 코드·설정 반영과 실제 운영 전환을 구분한다.
**기존 데이터의 유지·복구·이관 여부는 운영 담당자 재량이며 배포의 필수 조건이 아니다.**
이 문서는 기존 데이터 유지를 선택했을 때의 참고 절차다. 저장소 검증만으로 운영 토픽 생성·재구축·폐기가 완료된 것은 아니다.

## 확정 계약

| 은퇴할 토픽            | 새 토픽                   | 데이터 key             | Producer       | Consumer                    |
|------------------------|---------------------------|------------------------|----------------|-----------------------------|
| `channel.event`        | `channel.event.v2`        | `<channelId>`          | cowork-channel | cowork-project, cowork-chat |
| `channel.member.event` | `channel.member.event.v2` | `<channelId>:<userId>` | cowork-channel | cowork-chat, cowork-voice   |
| `project.event`        | `project.event.v2`        | `<projectId>`          | cowork-project | cowork-channel, cowork-chat |
| `project.member.event` | `project.member.event.v2` | `<projectId>:<userId>` | cowork-project | cowork-chat                 |

네 토픽 모두 `cleanup.policy=compact`이며 현재 상태와 삭제 이력을 전량 발행한다. Kafka null-value
tombstone을 사용하는 계약이 아니다. `PROJECTION_SNAPSHOT_COMPLETED`는 별도 예약 key로 각 partition에
발행한다. 키 의미가 바뀐 구 토픽의 원문을 새 토픽으로 복사하지 않는다.

`cowork-chat`의 기존 group ID는 유지한다. 이름에 들어 있는 `v2-projection`은 토픽 버전이 아니다.
`cowork-channel.project-event`와 `cowork-project.channel-state`도 유지하며, checkpoint는 topic별로
분리된다. Voice는 `cowork-voice.channel-member.v2`로 전환한다. Group 변경만으로 projection 행이
초기화되지는 않는다.

## 1. 점검과 쓰기 중단

1. 유지보수 시간에 대상 API·WebSocket 트래픽을 차단하고 자동 배포·자동 재시작이 구 버전을 다시
   기동하지 않도록 한다. `cowork-channel`, `cowork-project`, `cowork-chat`, `cowork-voice`의 모든
   replica를 중지한 뒤 projection을 초기화한다. 구·신 consumer가 같은 저장소에 동시에 쓰지 않는다.
2. 대상 DB/MongoDB의 복구 지점을 확보한다. 소유자 데이터와 삭제 이력은 초기화하지 않는다.
3. Config Server에 새 voice 설정이 반영되는지 확인한다. Vault, Config Server overrides, 컨테이너
   환경변수에 같은 키가 있으면 함께 변경한다. Go 기본값만 바꿔서는 원격 override가 바뀌지 않는다.
   로컬 실행의 `deploy/local/services/voice.sh`도 아래 기본값을 사용한다. 실행 전에 읽는 `.env`나 셸에
   명시한 값이 있으면 이 기본값보다 우선하므로 구 토픽·group override를 함께 정리한다.

   ```text
   KAFKA_TOPIC_CHANNEL_MEMBER_EVENT=channel.member.event.v2
   KAFKA_GROUP_ID_CHANNEL_MEMBER=cowork-voice.channel-member.v2
   ```

4. Channel DB와 Project DB 각각에서 outbox의 잔여 topic을 확인한다.

   ```sql
   SELECT topic, COUNT(*) AS pending, MIN(id) AS first_id
   FROM tb_kafka_outbox
   GROUP BY topic;
   ```

   이미 저장된 행의 topic/key/payload는 그 당시 계약이다. 원문을 v2로 재지정하거나 삭제하지 않는다.
   구 토픽은 남겨 두어 새 버전의 relay도 기존 목적지로 잔여 행을 발행할 수 있게 한다. FIFO 앞부분의
   실패 행은 뒤의 snapshot/marker도 막으므로 `attempts`, `last_error`를 확인하고 원인을 해결한다.
   v2는 소유자 DB의 현재 상태와 삭제 이력에서 새로 채운다.

## 2. 새 토픽 생성

기존 각 토픽의 partition 수·복제 계수와 broker 주소·인증 설정을 먼저 확인한다. 아래 Bash 명령은
Kafka CLI가 있는 관리 환경에서 실행한다. 저장소의 broker 컨테이너에서는 `kafka_cli_dir=/opt/kafka/bin`,
`KAFKA_BOOTSTRAP_SERVERS=kafka:9092`를 사용한다. 외부 클러스터에서는 주소와 CLI 경로를 교체하고,
인증이 필요하면 `KAFKA_CLIENT_CONFIG`에 관리용 client properties 파일 경로를 설정한다.

```bash
set -euo pipefail
kafka_cli_dir=/opt/kafka/bin
: "${KAFKA_BOOTSTRAP_SERVERS:?대상 broker 주소를 설정한다}"
: "${KAFKA_TOPIC_PARTITIONS:?기존 토픽과 같은 partition 수를 설정한다}"
: "${KAFKA_TOPIC_REPLICATION_FACTOR:?기존 토픽과 같은 복제 계수를 설정한다}"
kafka_admin_args=(--bootstrap-server "$KAFKA_BOOTSTRAP_SERVERS")
if [ -n "${KAFKA_CLIENT_CONFIG:-}" ]; then
  kafka_admin_args+=(--command-config "$KAFKA_CLIENT_CONFIG")
fi
for topic in channel.event.v2 channel.member.event.v2 project.event.v2 project.member.event.v2; do
  "$kafka_cli_dir/kafka-topics.sh" "${kafka_admin_args[@]}" \
    --create --if-not-exists --topic "$topic" \
    --partitions "$KAFKA_TOPIC_PARTITIONS" \
    --replication-factor "$KAFKA_TOPIC_REPLICATION_FACTOR" \
    --config cleanup.policy=compact
  "$kafka_cli_dir/kafka-topics.sh" "${kafka_admin_args[@]}" --describe --topic "$topic"
  "$kafka_cli_dir/kafka-configs.sh" "${kafka_admin_args[@]}" \
    --describe --entity-type topics --entity-name "$topic"
done
```

기존 토픽마다 partition 수·복제 계수가 다르면 해당 값으로 각각 생성한다. `--if-not-exists`는 이미
존재하는 토픽의 설정·데이터를 검증하거나 초기화하지 않는다. 이미 있는 v2 토픽은 계약, UUID, 설정,
과거 발행자를 확인한다. 잘못 생성되었더라도 같은 이름을 삭제·재생성하지 않는다.

운영 Kafka는 위 CLI 절차를 사용한다. 로컬 개발용 `kafka-init`의 전체 topic 목록은
`deploy/compose/stack.yaml`을 참고하되, 운영 broker 주소·인증·partition·복제 계수를 따로 지정한다.

## 3. projection 초기화

모든 구 replica가 중지된 상태에서 **각 서비스의 올바른 DB 연결**로 아래 명령을 실행한다. 새 topic의
checkpoint만 생성하고 기존 projection 행을 남기면 구 토픽에서 반영한 잔존 상태를 제거할 수 없다.
격리 테이블·collection은 감사 자료로 보존한다.

Channel DB:

```sql
START TRANSACTION;
DELETE FROM tb_project_projections;
DELETE FROM tb_kafka_projection_barriers
WHERE consumer_group = 'cowork-channel.project-event'
  AND topic_name IN ('project.event', 'project.event.v2');
DELETE FROM tb_kafka_projection_checkpoints
WHERE consumer_group = 'cowork-channel.project-event'
  AND topic_name IN ('project.event', 'project.event.v2');
COMMIT;
```

Project DB:

```sql
START TRANSACTION;
DELETE FROM tb_channel_projections;
DELETE FROM tb_kafka_projection_barriers
WHERE consumer_group = 'cowork-project.channel-state'
  AND topic_name IN ('channel.event', 'channel.event.v2');
DELETE FROM tb_kafka_projection_checkpoints
WHERE consumer_group = 'cowork-project.channel-state'
  AND topic_name IN ('channel.event', 'channel.event.v2');
COMMIT;
```

Voice MongoDB에서 다음을 실행한다. Snapshot 완료 정보와 invalid-record latch는 checkpoint 안에 있다.

```javascript
db.channel_memberships.deleteMany({});
db.projection_checkpoints.deleteMany({
  consumer_group: { $in: ['cowork-voice.channel-member', 'cowork-voice.channel-member.v2'] },
  topic: { $in: ['channel.member.event', 'channel.member.event.v2'] }
});
```

Chat은 collection을 수동 삭제하지 않는다. 다음 단계의 admin rebuild가 generation·lease를 관리하며
`channelMember`의 채팅 소유 필드를 보존한 채 membership 상태만 초기화한다.

## 4. 배포와 Chat rebuild

갱신된 Config Server 설정과 네 서비스 이미지를 배포한다. Project의 project/member snapshot은 team
관련 upstream을 기다리고, Channel snapshot은 project를 포함한 필수 projection의 catch-up을 기다린다.
Project의 GitHub repository snapshot은 channel state도 기다린다. Global readiness를 snapshot 발행
조건으로 임의 변경하면 순환 대기가 생길 수 있다.

기존 Chat dataset은 topic 변경을 감지해 `REBUILD_REQUIRED`가 된다. `sourceGeneration`을 임의로 올릴
필요는 없다. 저장소가 비어 처음 기동한 경우는 자동 bootstrap하며, 기존 dataset 네 개는 각각 명시적
rebuild를 요청한다. 서비스 내부 경로는 `/chat/admin/projections`, Gateway 경로는
`/api/chat/chat/admin/projections`다. Readiness가 닫히면 Eureka 경유 요청은 서비스에 도달하지 못할 수
있으므로 관리자가 접근할 수 있는 컨테이너 내부에서 다음을 실행한다.

아래는 chat이 실행 중인 VM에서의 명령이다. `OPERATOR_USER_ID`에 작업자의 실제 사용자 ID를 설정한다. 내부 관리
접속에서만 Gateway 신뢰 헤더를 사용하며, 이 목적의 서비스 포트를 외부에 공개하지 않는다.

```bash
: "${OPERATOR_USER_ID:?작업자의 사용자 ID를 설정한다}"
docker exec -i \
  -e OPERATOR_USER_ID="$OPERATOR_USER_ID" cowork-chat node <<'JS'
(async () => {
  const base = `http://127.0.0.1:${process.env.PORT || 8087}/chat/admin/projections`;
  const headers = {
    'Content-Type': 'application/json',
    'X-User-Id': process.env.OPERATOR_USER_ID,
    'X-User-Role': 'ADMIN',
  };
  for (const stream of ['channel', 'channelMember', 'project', 'projectMember']) {
    const response = await fetch(`${base}/${stream}/rebuild`, {
      method: 'POST', headers,
      body: JSON.stringify({ reason: '상태 토픽 v2 전환 및 현재 키 계약 재구축' }),
    });
    const body = await response.text();
    if (!response.ok) throw new Error(`${stream}: ${response.status} ${body}`);
    console.log(stream, body);
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
JS
```

현재 snapshot 재발행 간격은 300초이고, Chat rebuild는 요청 이후의 새 snapshot 완료도 요구한다.
300초는 완료 상한이 아니다. Upstream catch-up, snapshot 크기와 relay backlog가 더해진다. 상태 조회는
위와 같은 내부 관리 접속에서 `GET /chat/admin/projections`로 수행하며, 조회만 하려는 때 POST rebuild를
반복하지 않는다.

```bash
docker exec -i \
  -e OPERATOR_USER_ID="$OPERATOR_USER_ID" cowork-chat node <<'JS'
(async () => {
  const response = await fetch(`http://127.0.0.1:${process.env.PORT || 8087}/chat/admin/projections`, {
    headers: { 'X-User-Id': process.env.OPERATOR_USER_ID, 'X-User-Role': 'ADMIN' },
  });
  const body = await response.text();
  if (!response.ok) throw new Error(`${response.status} ${body}`);
  console.log(body);
})().catch(error => { console.error(error); process.exitCode = 1; });
JS
```

## 5. 전환 확인과 구 토픽 폐기

- Chat의 `channel`, `channelMember`, `project`, `projectMember`가 새 topic에서 `ACTIVE`이며 ready인지
  확인한다. Channel·Project의 `/actuator/health/readiness`, Voice·Chat의 `/health/ready`도 확인한다.
- 각 새 토픽을 earliest부터 확인해 데이터 key가 위 표와 일치하고 모든 partition에 유효한 완료 marker가
  있는지 확인한다. 새 계약 위반 격리가 없어야 한다. Marker key는 데이터 key 검사에서 구분한다.
- 실제 채널·프로젝트·멤버십 조회를 확인한 뒤 트래픽을 연다. 재시작만으로 초기화가 되었다고 판단하지
  않는다. Chat rebuild 결과와 서비스 readiness를 PR에 기록한다.
- 구 버전 replica·consumer가 남아 있지 않고, 두 소유자 DB의 구 topic outbox가 0건이며 이후에도
  증가하지 않는지 확인한다. 그 뒤에만 다음 명령으로 네 구 토픽을 폐기한다. 같은 이름은 재사용하지 않는다.

```bash
for topic in channel.event channel.member.event project.event project.member.event; do
  "$kafka_cli_dir/kafka-topics.sh" "${kafka_admin_args[@]}" --delete --topic "$topic"
done
```

`project.event-dlt`는 현재 state consumer의 처리 대상이 아니므로 신규 provisioning에서 제외했다.
운영에 남은 DLT는 격리 자료의 보관 필요와 실제 사용 여부를 확인한 뒤 별도로 정리한다.

## 실패 시 복구

트래픽을 닫은 상태에서 원인을 해결한다. 구·신 버전을 같은 projection 데이터에 혼용하거나, 새 토픽을
삭제·재생성하거나, broker offset만 reset하지 않는다. 코드만 구 버전으로 되돌리면 v2에서 발생한 변경이
구 토픽에 없을 수 있다. 되돌려야 하면 모든 writer를 멈추고 구 producer의 full snapshot, projection
재구축과 catch-up을 다시 수행하는 별도 전환으로 취급한다. 구 토픽 폐기 후에는 v2를 복구하는 방향을
사용한다.
