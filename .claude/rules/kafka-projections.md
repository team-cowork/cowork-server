---
paths:
  - "cowork-*/src/**/{kafka,messaging,projection,consumer,producer,event,outbox,client}/**"
  - "cowork-*/src/**/*{Projection,projection,Outbox,outbox,EventState,Tombstone,tombstone,AccessGuard}*.*"
  - "cowork-*/src/**/config/*Kafka*.*"
  - "cowork-*/src/**/*.client.ts"
  - "cowork-*/internal/**/{kafka,projection,relay,client}/**"
  - "cowork-*/internal/**/*{projection,outbox}*.go"
  - "cowork-*/internal/health/readiness.go"
  - "cowork-voice/internal/{config,infra/channel}/**"
  - "cowork-authorization/internal/{repository/refresh_token_repository.go,service/auth_service.go,service/event_service.go}"
  - "cowork-user/src/{Kafka/**,Users/Accounts.cs}"
  - "**/db/migration/**/*{kafka,projection,outbox,checkpoint,quarantine,event_state,tombstone}*.sql"
  - "cowork-config/src/main/resources/configs/cowork-*.yml"
  - "deploy/compose/*.yaml"
  - "deploy/prod/**/compose.yaml"
  - "deploy/**/*.sh"
---

# Kafka Projections

## Contracts

- Name new topics `<domain>[.<resource>...].<state|command|result|event>.v<major>`, with lowercase kebab-case segments. Keep environment and consumer names out of topics. Preserve deployed names unless the wire contract changes.
- Serialized keys, identity, partition routing, and incompatible payload changes require a new topic version. Keep partition count stable within a compacted state version. A consumer fallback cannot migrate retained keys.
- Provision snapshot-backed state topics with compact retention before producers start. Provision a `-dlt` only when a consumer uses one; state projections quarantine in their own store.
- Keep producer, consumer, snapshot marker, Config, and provisioning references consistent. Consumer group changes alone do not rebuild projection data.

## Publication

- Commit owner mutations and their records in one transactional outbox. Prevent completion markers from overtaking uncommitted lower outbox sequences.
- State records carry stable entity keys and source mutation versions. Snapshots retain those versions and deletion history; do not replace durable deletion events with Kafka null tombstones. Define equal-version handling.
- Publish full snapshots at startup and periodically. Serialize each aggregate's entire snapshot run across replicas with an ownership-safe lock; use a unique `snapshotId`.
- Emit a completion marker for every partition through the ordered outbox. Derived snapshots must wait for the upstream projections that affect that aggregate. Keep these dependencies acyclic; exclude action-only streams from snapshot readiness.

## Recovery and Readiness

- Persist `(group, topic, partition, next_offset)` with applied projection data, in the same transaction where supported. Verify topic identity, retained offset bounds, and dataset identity before resuming; broker group offsets are not the recovery authority.
- Use broker topic UUIDs where available. Chat's existing KafkaJS path instead uses `sourceGeneration`, MongoDB dataset identity, and offset bounds; do not extend that exception to new clients.
- Hold readiness and Eureka traffic until checkpoints reach startup high-watermarks and valid snapshot markers. Continue checking current high-watermarks. Projection-dependent APIs return `503` while catch-up or continuity checks fail.
- Malformed state records latch the projection unready until explicit repair/rebuild or a distinct full-snapshot recovery restores the gap. A single completion marker cannot clear the latch. Action-only poison records may advance after successful quarantine; transient storage failures must not advance offsets.
- If continuity cannot be proved, fence old workers and rebuild affected projections and checkpoints. Replacing owner data or Kafka can remove deletion history; rebuild dependent stores together and preserve consumer-owned fields.
- Data preservation is an operator choice. When preserving it, account for pending outbox records and retain deletion history before replacing topics. Retire old topics only after their producers stop and replacement consumers are ready.
