---
paths:
  - "**/db/migration/**/*.sql"
  - "**/*Entity.java"
  - "**/*Repository.java"
---

# Database Rules

- Never modify committed Flyway migration files (`V{n}__*.sql`). Add a new version file instead.
- All table names require the `tb_` prefix.
  - Index: `idx_tb_{table}_{column}`
  - Unique key: `uq_tb_{table}_{column}`
  - FK constraint: `fk_tb_{table}_{target}`
- Never add real FK constraints across services. Reference another service's ID via a column `COMMENT` indicating the source.
  ```sql
  -- correct
  team_id BIGINT NOT NULL COMMENT 'cowork-team의 tb_teams.id'
  ```
- Flyway migration file naming: `V{n}__{snake_case_description}.sql` (e.g. `V2__add_github_id.sql`)
- MongoDB services (`cowork-chat`, `cowork-voice`) do not use Flyway. Manage schema definitions in each service's `schema/` directory.
- Do not remove or transform the `_id` field from Mongoose documents sent to the client.
- Set `versionKey: false` in the schema to exclude the `__v` key.
