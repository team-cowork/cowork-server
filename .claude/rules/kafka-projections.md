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
  - "cowork-*/src/**/config/*Kafka*.*"
  - "cowork-*/src/**/entity/*EventState.*"
  - "cowork-*/src/**/entity/*Tombstone.*"
  - "cowork-*/src/**/repository/*EventStateRepository.*"
  - "cowork-*/src/**/repository/*TombstoneRepository.*"
  - "cowork-*/src/**/*AccessGuard.*"
  - "cowork-*/src/**/client/**"
  - "cowork-*/src/**/*.client.ts"
  - "cowork-*/internal/infra/kafka/**"
  - "cowork-voice/internal/config/**"
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
  - "deploy/**/*.sh"
  - "docs/kafka-*.md"
  - "deploy/compose/*.yaml"
  - "deploy/prod/**/*.yaml"
---

# Kafka Projection Rules

## Topic Names and Versions

- Application topics use `<domain>[.<resource>...].<contract>.v<major>`. Use lowercase dot-separated segments, kebab-case within a segment, and a positive integer major version. Do not include deployment environment, consumer service, partition count, or release date in the name; local and prod use separate Kafka datasets in this repository.
- For new streams, use `state` for reconstructible entity state, `command` for a request to the authoritative owner, `result` for its outcome, and `event` for an occurrence whose history matters. Classify retention and recovery by the actual contract, not the suffix alone. Register owner, consumers, key, cleanup policy, and snapshot support in `docs/development-guide.md`.
- Existing unversioned names are legacy version 1. Keep their base name when introducing a breaking version: `channel.event.v2`, `channel.member.event.v2`, `project.event.v2`, and `project.member.event.v2` are compacted **state** streams. Do not rename unrelated deployed topics merely for stylistic uniformity. Framework-owned names such as `springCloudBus` follow the framework's contract.
- Version the complete wire contract: serialized key, identity semantics, partition routing, and incompatible payload changes. Adding optional fields does not require a version bump when every reader remains compatible. A key encoding/meaning change requires a new topic; consumer fallback parsing cannot repair the old compacted keyspace.
- Keep partition count and key-to-partition routing stable for a deployed compacted state version. Compaction and ordering are partition-local; moving a key to another partition leaves a separate retained value. Plan repartitioning as a new topic and projection rebuild. Replication-factor changes alone do not change the key contract.
- A DLT, when the consumer actually uses one, is `<complete-source-topic>-dlt`, e.g. `billing.invoice.command.v1-dlt`. Provision it explicitly with delete retention and enough partitions for the recoverer's routing. Do not create DLTs for state streams that quarantine in their projection store.
- Consumer group IDs identify a consuming application/projection and are independent of the topic's contract version. Reuse a group only when checkpoint identity includes the topic and the old deployment has stopped; a new group alone never clears a projection. Keep listener, checkpoint, configuration defaults, and remote overrides consistent.

## Provisioning and Cutover

- Create state topics explicitly with `cleanup.policy=compact` before producers start; do not rely on broker auto-creation. Do not add `delete` retention to snapshot-backed topics without a designed rebuild/retention contract.
- Coordinate every producer, snapshot marker, consumer, config/default/override, launch script, and provisioning reference. Launch-script environment defaults can override Config Server values, so include `deploy/**/*.sh` in the reference audit. Reuse producer topic constants for completion markers. For the four v2 streams, follow `docs/kafka-state-topic-cutover.md`.
- Retaining or migrating existing data is at the operator's discretion, not a deployment prerequisite. When retaining existing data, the v2 cutover uses a maintenance window: stop old writers/consumers, account for pending outbox rows, create the new topics, rebuild the affected projection data and checkpoints, and wait for full snapshots and catch-up before reopening traffic. Rolling versions against the same projection store or copying raw old records into the new topic is not a migration strategy. A zero-downtime migration needs a separately designed dual-write/backfill and isolated consumer store.
- Do not rewrite a stored outbox topic blindly: its key/payload/partition belong to the old contract. Drain valid pending rows to the old topic before retirement; investigate poison rows rather than skipping them. Keep authoritative state and deletion history for the new full snapshot.
- Retire old topics only after every replacement consumer is ready and no old producer or pending outbox row can publish again. Removing a name from Compose does not delete an existing broker topic. Never reuse a retired name or delete/recreate a deployed topic in place.

## Event and Snapshot Contract

- Projection events use a stable entity key (or composite entity key), include the source mutation version (`occurredAt` in current contracts), and are safe under duplicate or out-of-order delivery. Retain deletion state and its version so a stale snapshot cannot resurrect it; define deterministic handling of equal versions.
- With `cleanup.policy=compact`, Kafka eventually retains the last appended value for each serialized key **within each partition**, not the greatest business timestamp. Old formats can remain indefinitely if no later value or Kafka tombstone replaces their keys; compaction is asynchronous and does not leave exactly one record at all times.
- Current state contracts represent deletion with a non-null JSON event (`DELETED`, `REMOVED`, or `LEAVE`) and persisted owner-side deletion history. This application tombstone is different from a Kafka tombstone (key + null value), which is eventually removed under `delete.retention.ms`. Do not replace durable deletion events with null values or prune source deletion history without a coordinated rebuild policy.
- Keep the outbox-to-producer boundary JSON-native; never pass a JSON node from a different serializer/Jackson generation, and never let an invalid top-level `null` become a Kafka tombstone.
- Producers publish full snapshots, including retained deletions, on startup (once required upstream projections are ready) and periodically. New or explicitly rebuilt projections replay from the broker's earliest available offset; an ordinary restart can resume a verified persisted checkpoint under the rules below. `auto.offset.reset=earliest` is only a missing-offset fallback, not a rebuild command.
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
- Persist and verify the broker topic UUID with the checkpoint whenever the client API exposes it; also verify that the checkpoint is within the retained offset range and belongs to the same projection dataset. When identity or continuity cannot be verified, fail closed and use a fenced rebuild; a numeric offset or full replay into uncleared data cannot prove continuity after source replacement.
- `cowork-chat` currently resumes with operator-managed `sourceGeneration`, MongoDB dataset identity, and retained offset checks because its KafkaJS path does not expose broker topic UUIDs. Topic/group/source-generation changes require the explicit admin rebuild. This is an operational constraint, not equivalent to broker-verified identity: follow the unresolved recovery work in `docs/todo/items/31-performance/projection-incremental-resume.md`, and do not generalize the exception to new clients.
- Capture each relevant topic partition's end offset at startup and keep readiness/Eureka traffic closed until the shared projection checkpoints reach every captured offset. Projection-dependent APIs return `503`, never a false `403` or partial/empty success, while catching up.
- Continue comparing required checkpoints with the current broker high-watermarks after startup. Close readiness again whenever a required projection falls behind or its broker/checkpoint state cannot be verified, and reopen it only after catch-up.
- An action-only malformed record may be quarantined and advance the checkpoint after quarantine succeeds. A malformed snapshot-backed state record must also persist an invalid-record latch and close readiness immediately; clear it only through an explicit repair/rebuild or distinct serialized full-snapshot recovery that proves the gap was restored. One completion marker alone must never clear the latch. Transient storage/infrastructure failures must not advance either the checkpoint or consumer position.
- Topic UUID checks detect replacement when broker metadata is refreshed, not instantly. A client without topic identity cannot detect an overlapping same-name replacement from offsets alone. Replacing an authoritative owner database or Kafka dataset can erase deletion history; rebuild related projection data, barriers, and checkpoints together while old workers are stopped/fenced before reopening traffic. Preserve consumer-owned fields when clearing mixed-ownership collections.

## Test Scope

- These production architecture rules do not create an exception to the repository test policy. Write unit tests only for core business decisions, authorization, or security behavior.
- Do not add tests whose sole purpose is to freeze Kafka serialization, delivery idempotency, outbox ordering, checkpoints, readiness, snapshot markers, quarantine, retry, or recovery mechanics. Validate non-business configuration and schema concerns with an appropriate static check when one is genuinely required.

Kafka semantics: [log compaction](https://kafka.apache.org/40/design/design/) and [topic configuration](https://kafka.apache.org/41/generated/topic_config.html). Naming, snapshots, readiness, and cutover requirements above are this repository's application contract, not Kafka defaults.
