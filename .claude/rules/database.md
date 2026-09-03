---
paths:
  - "**/db/migration/**/*.sql"
  - "**/*Entity.kt"
  - "**/*Entity.java"
  - "**/*Repository.kt"
  - "**/*Repository.java"
  - "**/*.schema.ts"
---

# Database Rules

See CLAUDE.md for the table-naming and cross-service-FK conventions. This file covers the details that only apply to specific file types.

## Migrations (`V{n}__*.sql`)

- Never edit a committed migration — Flyway validates checksums; the Go services' runner records applied versions and skips them on later starts, so edits to an applied script are not reapplied. Add a new version file.
- Name new files `V{n}__{snake_case_description}.sql`, e.g. `V2__add_github_id.sql`.
- Pick `{n}` by looking at the highest existing version **in that service only** — version sequences are per-service, not global.

## Mongoose Schemas (`cowork-chat`)

- Keep `_id` on documents returned to clients; never strip or rename it.
- Set `versionKey: false` in `@Schema(...)` so `__v` is not serialized.
