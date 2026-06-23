# cowork Server

## Overview

Team collaboration platform backend — polyglot microservices monorepo.

- Languages per service: Kotlin (`gateway`, `config`, `channel`, `preference`, `project`, `team`), Go (`authorization`, `notification`, `voice`), TypeScript (`chat`, `promotion`), Elixir (`user`)
- Frameworks: Spring Boot / Spring Cloud Gateway / Eureka / OpenFeign / Vert.x (Kotlin), NestJS / Nuxt.js (TS), Gin·Chi (Go), Phoenix (Elixir), LiveKit (`voice`)
- Data & infra: MySQL, MongoDB, Redis, Elasticsearch, Flyway, Kafka, Docker, Vault, Prometheus/Grafana

## Agent Working Rules

Project-specific conventions only. Universal best practices (no hardcoded secrets, input validation, least privilege, etc.) are assumed and intentionally omitted.

### Identity & Gateway

- `cowork-gateway` is the only place that validates JWT. Downstream services must NOT parse or validate JWT — trust the Gateway-forwarded headers instead:
  - `X-User-Id`: `Long`
  - `X-User-Role`: `ADMIN` | `MEMBER`
- Don't expose service access paths that bypass the Gateway in production-oriented code or config.
- Configure CORS only at `cowork-gateway`; no per-service CORS unless documented.

### Database

- Never modify a committed Flyway migration (`V{n}__*.sql`); add a new version instead.
- Migration naming: `V{n}__{snake_case_description}.sql` (e.g. `V2__add_github_id.sql`).
- Relational tables use the `tb_` prefix. Constraint naming:
  - Index: `idx_tb_{table}_{column}`
  - Unique: `uq_tb_{table}_{column}`
  - FK-like: `fk_tb_{table}_{target}`
- Never create real cross-service foreign keys. Store another service's ID as a plain column and document the source in its `COMMENT`:

  ```sql
  team_id BIGINT NOT NULL COMMENT 'cowork-team의 tb_teams.id'
  ```

- MongoDB-backed services (`cowork-chat`, `cowork-voice`) don't use Flyway — keep schema definitions in each service's `schema/` directory.
- For Node/Mongoose services (`cowork-chat`): keep `_id` on documents returned to clients; set `versionKey: false` to drop `__v`.

### Configuration

- Shared config is served by the `cowork-config` Config Server. Local-only overrides go in `application-local.yml`, which stays gitignored.

### Service Startup Order

1. `cowork-config` (Eureka + Config Server)
2. `cowork-gateway`
3. Business services (`authorization`, `user`, `team`, `project`, `channel`, …) in any order