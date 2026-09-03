---
name: migration-guide
description: Guide for DB schema changes and Entity modifications — impact analysis, migration and model/DTO/repository/service/test change order, JPA DDL strategy, and 2-phase column deletion.
---

# DB Migration Guide

## Impact Analysis

Identify the authoritative service, database engine, existing migration runner, affected queries/DTOs, and compatibility with deployed data. Check consumers and projections when event payloads change.

## Change Order

1. Add a new `src/main/resources/db/migration/V{n}__{description}.sql` for relational schema changes. Select the next version within the target service and never modify committed migrations.
2. Update the entity/model or MongoDB schema to match the new storage contract.
3. Update request/response DTOs and mappings as needed.
4. Adjust repositories and bound queries, then services and transactional outbox/projection contracts.
5. Update relevant tests and schema/API documentation; verify the existing-data migration path when the schema change requires it.

## Runtime Differences

- Team/channel/project use JPA with `ddl-auto: none` and Flyway.
- Roadmap uses R2DBC for queries and JDBC Flyway at startup.
- Preference uses PostgreSQL Flyway with Vert.x.
- Authorization/notification use a Go migration runner that parses `V{n}__*.sql`.
- User runs Flyway CLI before its Elixir release.
- Chat/voice use MongoDB models/schemas rather than SQL migrations.

Production migrations use those configured runners; do not replace them with ad hoc manual SQL or enable automatic JPA schema updates.

## Compatibility

For column removal, first deploy code that stops reading/writing the old column, then remove it in a later migration once old replicas and consumers are gone. Plan data backfill and recovery for destructive changes. Never add a real FK to another service's database; document scalar reference IDs with their owner.
