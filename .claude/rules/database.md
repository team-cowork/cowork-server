---
paths:
  - "**/db/migration/**/*.sql"
  - "cowork-*/src/**/*{Entity,Repository}.{kt,java}"
  - "cowork-*/src/**/{entity,repository}/**"
  - "cowork-*/src/**/*.schema.ts"
  - "cowork-*/internal/{model,repository,infra/mysql}/**"
  - "cowork-user/lib/cowork_user/**/*.ex"
---

# Database

- New relational tables use `tb_`; constraints use `idx_tb_{table}_{column}`, `uq_tb_{table}_{column}`, and `fk_tb_{table}_{target}`. Preserve existing unprefixed tables, including Preference and ShedLock tables.
- Cross-service references are scalar IDs without database foreign keys. Record the source service, table, and column in the column comment.
- Add migrations as `V{n}__{snake_case_description}.sql`, using the next version within that service. Do not edit committed migrations.
- Chat responses retain MongoDB `_id`; Mongoose schemas use `versionKey: false`.
