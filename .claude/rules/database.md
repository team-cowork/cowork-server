---
paths:
  - "**/db/migration/**/*.sql"
  - "**/*Entity.kt"
  - "**/*Entity.java"
  - "**/entity/**/*.kt"
  - "**/entity/**/*.java"
  - "**/*Repository.kt"
  - "**/*Repository.java"
  - "**/*.schema.ts"
---

# Database Rules

## Naming and Ownership

- Relational tables use the `tb_` prefix, with `idx_tb_{table}_{column}`, `uq_tb_{table}_{column}`, and `fk_tb_{table}_{target}` constraint names.
- Preserve the existing exceptions: preference's `account_channel_notification`, `resource_setting`, `project_role_definition`, and `account_project_role`, and the third-party `shedlock` table. Do not introduce new unprefixed tables.
- Never create real cross-service foreign keys. Store another service's ID as a scalar column and document its source with a column comment:

  ```sql
  team_id BIGINT NOT NULL COMMENT 'cowork-team의 tb_teams.id'
  ```

  Use `COMMENT ON COLUMN` for PostgreSQL.

## Migrations (`V{n}__*.sql`)

- Never edit a committed migration — Flyway validates checksums; the Go services' runner records applied versions and skips them on later starts, so edits to an applied script are not reapplied. Add a new version file.
- Name new files `V{n}__{snake_case_description}.sql`, e.g. `V2__add_github_id.sql`.
- Pick `{n}` by looking at the highest existing version **in that service only** — version sequences are per-service, not global.

## Mongoose Schemas (`cowork-chat`)

- Keep `_id` on documents returned to clients; never strip or rename it.
- Set `versionKey: false` in `@Schema(...)` so `__v` is not serialized.
