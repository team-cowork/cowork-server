---
paths:
  - "cowork-*/src/**/kafka/**"
  - "cowork-*/src/**/messaging/**"
  - "cowork-*/src/**/projection/**"
  - "cowork-*/src/**/consumer/**"
  - "cowork-*/src/**/producer/**"
  - "cowork-*/src/**/outbox/**"
  - "cowork-*/src/**/client/**"
  - "cowork-*/src/**/*.client.ts"
  - "cowork-*/internal/infra/kafka/**"
  - "cowork-*/internal/**/projection/**"
  - "cowork-*/internal/relay/**"
  - "cowork-*/internal/client/**"
  - "cowork-*/internal/health/readiness.go"
  - "cowork-*/internal/**/*outbox*.go"
  - "cowork-user/lib/cowork_user/kafka/**"
  - "**/db/migration/**/*kafka*.sql"
  - "**/db/migration/**/*projection*.sql"
  - "**/db/migration/**/*outbox*.sql"
  - "**/db/migration/**/*checkpoint*.sql"
  - "**/db/migration/**/*quarantine*.sql"
  - "cowork-config/src/main/resources/configs/cowork-*.yml"
  - "docker-compose*.yml"
---

# Kafka Projection Rules

## Communication Boundary

- Use Kafka-backed local projections for service-to-service reads whenever eventual consistency is acceptable. Do not add an internal REST/Feign client merely to read another service's state.
- Keep synchronous HTTP only when the caller must receive an immediate command result, generated ID, or validation error. Document each exception at the client; external provider calls such as GitHub App APIs are not internal service reads.
- Do not implement synchronous Kafka request/reply as a substitute for REST. Use commands/events for asynchronous workflows and local projections for reads.

## Event and Snapshot Contract

- Projection events use a stable entity key (or composite entity key), include `occurredAt`, and are safe under duplicate or out-of-order delivery. Consumers must retain tombstones/version timestamps so a stale snapshot cannot resurrect deleted state.
- State topics must be created explicitly with log compaction. Producers publish a full snapshot on startup and periodically; projection consumers read from the beginning with a dedicated consumer group.
- After every full state snapshot, the producer appends a `PROJECTION_SNAPSHOT_COMPLETED` control record to each topic partition through the same ordered outbox. Consumers require a valid marker for every partition as well as the startup high-watermark; an empty newly created topic is not evidence that a source snapshot completed.
- Do not put action-only streams with no reconstructible source snapshot in the snapshot-completion readiness set. Classify the stream contract explicitly instead of publishing an empty completion marker.
- Snapshot records reuse the source row's mutation timestamp/version; never stamp a snapshot with the publish time, because it could outrank a concurrent delete tombstone and resurrect removed state.

## Outbox Ordering

- A database-backed state mutation and its Kafka record must be joined by a transactional outbox. Do not rely on an `afterCommit` best-effort send for deletes or other state that a current-row snapshot cannot reconstruct.
- An outbox relay must not publish a later completion marker past an uncommitted lower sequence/auto-increment row. Use a database-appropriate locking read or serialize every producer transaction with a transaction-scoped advisory lock; a PostgreSQL `FOR UPDATE` query by itself cannot see an uncommitted sequence row.

## Consumer Checkpoints and Readiness

- A projection consumer persists `(group, topic, partition, next_offset)` in the projection store after a successful apply, in the same database transaction where supported. On assignment it seeks from that checkpoint, not the broker group offset; a missing checkpoint starts at the topic beginning.
- Capture each relevant topic partition's end offset at startup and keep readiness/Eureka traffic closed until the shared projection checkpoints reach every captured offset. Projection-dependent APIs return `503`, never a false `403` or partial/empty success, while catching up.
- Malformed contract records may be quarantined and advance the projection checkpoint only after quarantine succeeds. Transient storage/infrastructure failures must not advance either the checkpoint or consumer position.
- Kafka topic names are immutable in deployed environments; do not delete and recreate a topic in place. Numeric offsets cannot prove topic identity after same-name recreation. Replacing the Kafka cluster/data volume therefore requires rebuilding the related projection tables and checkpoints together before traffic is reopened.
