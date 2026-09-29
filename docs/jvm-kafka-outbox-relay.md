# JVM Kafka outbox relay 운영 절차

`cowork-channel`, `cowork-team`, `cowork-project`는 `shared/jvm-outbox`의 공통 relay를 사용한다. producer transaction은 `tb_kafka_outbox_fence`의 singleton row를 잠근 뒤 outbox를 추가하고, relay는 같은 fence를 잠근 짧은 transaction에서 한 행만 claim한다. Kafka 발행과 acknowledgement 대기는 claim transaction이 끝난 뒤 수행한다.

relay의 전달 보장은 at-least-once다. Kafka 발행 성공 후 outbox 삭제 전에 프로세스가 종료되면 같은 event가 다시 발행될 수 있으므로 consumer의 멱등 처리는 계속 유지한다.

## 상태와 순서

| 상태·유형 | 동작 |
| --- | --- |
| `PENDING` | `next_attempt_at`이 지난 행을 claim하여 발행한다. |
| `QUARANTINED` | 자동 발행에서 제외되며 운영자가 원인을 수정하고 재처리할 때까지 보존한다. |
| `PAYLOAD` | JSON object로 역직렬화할 수 없는 영구 오류이며 첫 실패에 격리한다. |
| `KAFKA_PUBLISH` | Kafka 발행 실패이며 지수 backoff와 jitter를 적용하고 최대 시도 뒤 격리한다. |

일반 event는 `(topic, event_key)`별 가장 오래된 행만 선택한다. 한 key의 실패는 같은 key의 후속 event만 막고 다른 key는 계속 처리한다. `is_barrier = TRUE`인 `PROJECTION_SNAPSHOT_COMPLETED` 행은 자신보다 오래된 모든 outbox가 끝난 뒤 발행된다. 대기 중인 barrier보다 새로운 일반 event는 무관한 key라면 계속 처리하지만, 이후 completion barrier는 앞선 barrier를 추월할 수 없다.

`claim_owner`와 `claim_until`은 stale worker의 finalize를 차단한다. 정상 종료 시 현재 process의 claim을 반환하고, 비정상 종료 시에는 lease가 만료된 뒤 다른 replica가 회수한다.

## 설정

공통 기본값은 `cowork-config/src/main/resources/configs/application.yml`에 있다.

| 설정 | 기본값 | 의미 |
| --- | ---: | --- |
| `kafka.outbox.batch-size` | `100` | 한 scheduling cycle의 최대 처리 건수 |
| `kafka.outbox.claim-lease-ms` | `30000` | claim 소유권 유효 시간 |
| `kafka.outbox.send-timeout-ms` | `10000` | Kafka acknowledgement 대기 상한 |
| `kafka.outbox.max-attempts` | `8` | Kafka 발행 실패 격리 기준 |
| `kafka.outbox.backoff-initial-ms` | `5000` | 최초 재시도 기준 지연 |
| `kafka.outbox.backoff-max-ms` | `600000` | 재시도 지연 상한 |

`kafka.outbox.claim-lease-ms`는 항상 `kafka.outbox.send-timeout-ms`보다 커야 한다. 잘못된 값은 애플리케이션 시작 시 거부된다.

## 지표

| Prometheus 지표 | 확인 내용 |
| --- | --- |
| `cowork_kafka_outbox_pending` | 발행 대기 행 수 |
| `cowork_kafka_outbox_oldest_seconds` | 가장 오래된 대기 행의 지연 |
| `cowork_kafka_outbox_retries_total` | `failure_type`별 재시도 횟수 |
| `cowork_kafka_outbox_quarantined_current` | 현재 격리 행 수 |
| `cowork_kafka_outbox_quarantined_total` | 새로 격리된 누적 행 수 |
| `cowork_kafka_outbox_publish_seconds` | Kafka acknowledgement 지연 |
| `cowork_kafka_outbox_observation_success` | 최근 DB backlog 관측 성공 여부 |

공유 DB gauge는 replica별 값을 합산하지 않고 `max`로 집계한다. `pending`, `oldest_seconds`, `quarantined_current` 증가와 `observation_success = 0`을 함께 확인한다.

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

`fence_count`는 `1`, `unmarked_completion_count`는 `0`이어야 한다. migration과 장애 복구 rehearsal 결과를 배포 기록에 남긴다.

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
