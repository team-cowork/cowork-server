---
name: database-schema
description: Database schema design guide — table naming, column conventions, index strategy, and JPA entity mapping patterns.
---

# Database Schema Design Guide

## Determine the Storage and Migration Owner

Read the target service's build file, migrations, and `AGENTS.md`/`CLAUDE.md` before proposing a schema. Existing tooling is already defined:

- Spring MySQL services: Flyway; roadmap queries through R2DBC.
- Authorization/notification: MySQL with a custom `V{n}__*.sql` runner.
- User: MySQL with Flyway CLI in the container entrypoint, Ecto at runtime.
- Preference: PostgreSQL with Flyway and Vert.x SQL clients.
- Chat/voice: MongoDB schemas/models, without SQL migrations.

Do not ask the user to choose Flyway versus Liquibase for an existing service.

## Naming Conventions

- New relational tables use `tb_` plus snake_case domain names, such as `tb_projects`.
- Columns use `snake_case`; reference IDs use `<domain>_id`.
- Index, unique, and foreign-key constraints use `idx_tb_{table}_{column}`, `uq_tb_{table}_{column}`, and `fk_tb_{table}_{target}`.
- Preserve the four pre-existing unprefixed preference tables and third-party `shedlock`; do not extend those exceptions.
- Never create cross-service foreign keys or ORM relationships. Store the owner service's ID as a scalar and document its source in a column comment.

```sql
team_id BIGINT NOT NULL COMMENT 'cowork-team의 tb_teams.id'
```

PostgreSQL uses `COMMENT ON COLUMN` for the equivalent source annotation. A real FK is only appropriate when both tables belong to the same service.

## Columns and Indexes

Match the service's existing ID and timestamp representation. MySQL `AUTO_INCREMENT`/`DATETIME(6)` is not PostgreSQL syntax, and projection/checkpoint tables can have composite keys. Do not force the same columns onto every table.

Choose indexes from the actual filters, joins, uniqueness requirements, and ordering. Check execution plans for performance changes; low-cardinality fields are not an automatic reason to add or reject an index.

## Migrations

Place new relational migrations in `src/main/resources/db/migration/V{n}__{snake_case_description}.sql`. Choose the next unused version within that service. Never edit a committed migration; add a new version.

Keep JPA `ddl-auto: none` as configured and apply migrations through the service's existing startup runner. MongoDB changes belong in the service's schema/model code and relevant schema documentation.

## JPA Entity Mapping

Apply this section only to team/channel/project. Follow the local entity and repository naming conventions, keep cross-service IDs scalar, and use lazy associations only for relationships owned by the same service. Keep entity mappings and migration column names/types consistent. Roadmap, preference, and the non-JVM services use their own persistence APIs.
