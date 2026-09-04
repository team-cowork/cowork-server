---
paths:
  - "cowork-*/src/**/kafka/**"
  - "cowork-*/src/**/messaging/**"
  - "cowork-*/src/**/projection/**"
  - "cowork-*/src/**/*Projection*.*"
  - "cowork-*/src/**/*projection*.*"
  - "cowork-*/src/**/consumer/**"
  - "cowork-*/src/**/producer/**"
  - "cowork-*/src/**/event/**"
  - "cowork-*/src/**/outbox/**"
  - "cowork-*/src/**/entity/*EventState.*"
  - "cowork-*/src/**/entity/*Tombstone.*"
  - "cowork-*/src/**/repository/*EventStateRepository.*"
  - "cowork-*/src/**/repository/*TombstoneRepository.*"
  - "cowork-*/src/**/*AccessGuard.*"
  - "cowork-*/src/**/client/**"
  - "cowork-*/src/**/*.client.ts"
  - "cowork-*/internal/infra/kafka/**"
  - "cowork-voice/internal/infra/channel/**"
  - "cowork-*/internal/**/projection/**"
  - "cowork-*/internal/relay/**"
  - "cowork-*/internal/client/**"
  - "cowork-*/internal/health/readiness.go"
  - "cowork-*/internal/**/*outbox*.go"
  - "cowork-user/lib/cowork_user/kafka/**"
  - "cowork-user/lib/cowork_user/accounts.ex"
  - "cowork-authorization/internal/repository/refresh_token_repository.go"
  - "cowork-authorization/internal/service/auth_service.go"
  - "cowork-authorization/internal/service/event_service.go"
  - "**/db/migration/**/*kafka*.sql"
  - "**/db/migration/**/*projection*.sql"
  - "**/db/migration/**/*outbox*.sql"
  - "**/db/migration/**/*checkpoint*.sql"
  - "**/db/migration/**/*quarantine*.sql"
  - "**/db/migration/**/*event*state*.sql"
  - "**/db/migration/**/*tombstone*.sql"
  - "cowork-config/src/main/resources/configs/cowork-*.yml"
  - "docker-compose*.yml"
---

# Kafka Projection Rules

## Event and Snapshot Contract

- Projection events use a stable entity key (or composite entity key), include `occurredAt`, and are safe under duplicate or out-of-order delivery. Consumers must retain tombstones/version timestamps so a stale snapshot cannot resurrect deleted state.
- Keep the outbox-to-producer boundary JSON-native and verify the serialized wire tree in a core unit test; never pass a JSON node from a different serializer/Jackson generation, and never let an invalid top-level `null` become a Kafka tombstone.
- State topics must be created explicitly with log compaction. Producers publish a full snapshot on startup and periodically; projection consumers read from the beginning with a dedicated consumer group.
- After every full state snapshot, the producer appends a `PROJECTION_SNAPSHOT_COMPLETED` control record to each topic partition through the same ordered outbox. Consumers require a valid marker for every partition as well as the startup high-watermark; an empty newly created topic is not evidence that a source snapshot completed.
- Serialize every full snapshot run for the same output aggregate across replicas and startup/periodic triggers with one ownership-safe lock whose lifetime covers the complete run; use a session lock or renewable owner token, never an unrenewed fixed TTL. Give each run a unique `snapshotId`; consumers persist the last and recovery IDs so a repeated marker cannot masquerade as a fresh recovery snapshot.
- When a produced snapshot is derived from upstream projections, publish its completion marker only after those required projections have reached their current broker high-watermarks. Do not let a downstream consumer open readiness on a causally incomplete snapshot.
- Keep snapshot dependencies acyclic. When two services consume each other's state topics, gate each emitted aggregate only on the upstream streams that can mutate that aggregate instead of using the service's global readiness indiscriminately.
- Do not put action-only streams with no reconstructible source snapshot in the snapshot-completion readiness set. Classify the stream contract explicitly instead of publishing an empty completion marker.
- Snapshot records reuse the source row's mutation timestamp/version; never stamp a snapshot with the publish time, because it could outrank a concurrent delete tombstone and resurrect removed state.

## Outbox Ordering

- A database-backed state mutation and its Kafka record must be joined by a transactional outbox. Do not rely on an `afterCommit` best-effort send for deletes or other state that a current-row snapshot cannot reconstruct.
- An outbox relay must not publish a later completion marker past an uncommitted lower sequence/auto-increment row. Use a database-appropriate locking read or serialize every producer transaction with a transaction-scoped advisory lock; a PostgreSQL `FOR UPDATE` query by itself cannot see an uncommitted sequence row.

## Consumer Checkpoints and Readiness

- A projection consumer persists `(group, topic, partition, next_offset)` in the projection store after a successful apply, in the same database transaction where supported. Broker group offsets are never the recovery authority.
- Persist and verify the broker topic UUID with the checkpoint whenever the client API exposes it. If the client cannot expose topic identity, every process restart or forced recovery must create a durable replay generation, atomically reset each assigned checkpoint and snapshot barrier to the broker earliest offset, fence stale replica leases, and complete a fresh replay before readiness opens; never trust a numeric checkpoint across that boundary.
- Capture each relevant topic partition's end offset at startup and keep readiness/Eureka traffic closed until the shared projection checkpoints reach every captured offset. Projection-dependent APIs return `503`, never a false `403` or partial/empty success, while catching up.
- Continue comparing required checkpoints with the current broker high-watermarks after startup. Close readiness again whenever a required projection falls behind or its broker/checkpoint state cannot be verified, and reopen it only after catch-up.
- An action-only malformed record may be quarantined and advance the checkpoint after quarantine succeeds. A malformed snapshot-backed state record must also persist an invalid-record latch and close readiness immediately; clear it only through an explicit repair/rebuild or distinct serialized full-snapshot recovery that proves the gap was restored. One completion marker alone must never clear the latch. Transient storage/infrastructure failures must not advance either the checkpoint or consumer position.
- Kafka topic names are immutable in deployed environments; do not delete and recreate a topic in place. UUID-aware clients detect replacement immediately. A client without topic identity cannot prove continuity for an overlapping same-name replacement until its fenced replay boundary, so topic immutability and a coordinated projection rebuild are mandatory. Replacing an authoritative owner database or Kafka dataset can erase tombstone history; rebuild the related projection tables, barriers, and checkpoints together before traffic is reopened.
