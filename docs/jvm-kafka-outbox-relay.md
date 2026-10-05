# JVM Kafka outbox relay 운영 절차

`cowork-channel`, `cowork-team`, `cowork-project`의 relay 전환·격리 복구 절차다.
설정 기본값은 [`configs/application.yml`](../cowork-config/src/main/resources/configs/application.yml),
상태·claim·barrier 구현은 각 모듈의 `KafkaOutboxRelay`를 기준으로 한다.
전환·장애 복구의 남은 확인은 [TODO](./todo/items/18-reliability/jvm-kafka-outbox-relay.md)에 기록한다.

공유 DB backlog gauge는 replica별로 합산하지 않고 `max`로 확인한다. backlog·최장 대기·격리 증가와
`cowork_kafka_outbox_observation_success`를 함께 확인한다. 한 key의 격리가 후속 event와 snapshot 완료를
막을 수 있으므로 단순 총량뿐 아니라 막힌 key도 확인한다.

## 배포와 migration 검증

구버전 writer는 producer fence를 사용하지 않는다. 세 서비스 각각에서 모든 replica를 중지하고 migration을 적용한 뒤 동일 버전 replica를 시작하는 maintenance 배포를 수행한다. 구버전과 신버전 relay를 같은 DB에서 동시에 실행하지 않는다.

적용 전 운영 데이터 사본에서 payload 원문을 출력하지 않고 다음 상태를 확인한다.

```sql
SELECT COUNT(*) AS pending_count,
       COALESCE(MAX(TIMESTAMPDIFF(SECOND, created_at, CURRENT_TIMESTAMP(6))), 0) AS oldest_seconds,
       SUM(JSON_VALID(payload) = 0) AS invalid_json_count
FROM tb_kafka_outbox;
```

서비스별 migration은 `cowork-channel`의 `V24`, `cowork-team`의 `V18`, `cowork-project`의 `V22`다. 적용 후 schema와 기존 completion marker backfill을 확인한다.

```sql
SELECT column_name, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'tb_kafka_outbox'
  AND column_name IN (
      'is_barrier', 'status', 'next_attempt_at', 'failure_type',
      'claim_owner', 'claim_until', 'updated_at'
  )
ORDER BY ordinal_position;

SELECT COUNT(*) AS fence_count
FROM tb_kafka_outbox_fence
WHERE id = 1;

SELECT COUNT(*) AS unmarked_completion_count
FROM tb_kafka_outbox
WHERE LEFT(event_key, 40) = '__cowork_projection_snapshot_complete__:'
  AND is_barrier = FALSE;
```

`fence_count`는 `1`, `unmarked_completion_count`는 `0`이어야 한다. migration과 수동 장애 복구 점검 결과를 배포 기록에 남긴다.

## 격리 조회와 재처리

일반 조회에서는 payload 원문 대신 길이와 hash만 확인한다.

```sql
SELECT id,
       topic,
       event_key,
       attempts,
       failure_type,
       LEFT(last_error, 500) AS last_error,
       CHAR_LENGTH(payload) AS payload_length,
       SHA2(payload, 256) AS payload_sha256,
       created_at,
       updated_at
FROM tb_kafka_outbox
WHERE status = 'QUARANTINED'
ORDER BY id;
```

payload 확인과 수정은 접근 기록이 남는 제한된 DB 세션에서만 수행하고 로그·메신저·issue에 원문을 복사하지 않는다. 같은 `(topic, event_key)`에 격리 행이 여러 개라면 가장 낮은 `id`부터 처리한다.

수정이 필요한 경우 parameter binding을 지원하는 관리 도구로 `:repaired_payload`를 전달하고 다음 transaction을 실행한다. 수정이 필요하지 않은 Kafka 일시 장애도 payload 대입을 제외한 같은 상태 초기화 절차를 사용한다.

```sql
START TRANSACTION;

SELECT id, status, attempts, failure_type
FROM tb_kafka_outbox
WHERE id = :outbox_id
FOR UPDATE;

UPDATE tb_kafka_outbox
SET payload = :repaired_payload,
    status = 'PENDING',
    attempts = 0,
    next_attempt_at = CURRENT_TIMESTAMP(6),
    failure_type = NULL,
    last_error = NULL,
    claim_owner = NULL,
    claim_until = NULL
WHERE id = :outbox_id
  AND status = 'QUARANTINED';

COMMIT;
```

업데이트 결과가 정확히 한 행인지 확인하고 `pending`, `published`, `quarantined_current` 지표가 수렴하는지 관찰한다. payload를 직접 삭제하여 key 순서를 우회하지 않는다.
