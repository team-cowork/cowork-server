# cowork Server

Team collaboration platform backend — polyglot microservices monorepo.

## Modules

Language and framework details live in each module's build file — read that, not this table, for versions and dependencies. What the table records is the part you cannot infer from any single file: **which build system owns the module**.

| Module                 | Language                                 | Built by                                                                                              |
|------------------------|------------------------------------------|-------------------------------------------------------------------------------------------------------|
| `cowork-gateway`       | Kotlin                                   | Gradle                                                                                                |
| `cowork-config`        | Kotlin                                   | Gradle                                                                                                |
| `cowork-channel`       | Kotlin                                   | Gradle                                                                                                |
| `cowork-team`          | Kotlin                                   | Gradle                                                                                                |
| `cowork-roadmap`       | Java                                     | Gradle                                                                                                |
| `cowork-project`       | Kotlin                                   | **Maven** — `pom.xml` is the source of truth; `build.gradle.kts` only delegates to `mvnw`             |
| `cowork-preference`    | Kotlin (Vert.x)                          | **Amper** — `module.yaml` is the source of truth; `build.gradle.kts` only delegates to the Kotlin CLI |
| `cowork-user`          | Elixir (Plug/Cowboy + Ecto — no Phoenix) | Mix                                                                                                   |
| `cowork-authorization` | Go (Chi)                                 | Go modules + per-module `Makefile`                                                                    |
| `cowork-notification`  | Go (Gin)                                 | Go modules + per-module `Makefile`                                                                    |
| `cowork-voice`         | Go (Chi, LiveKit)                        | Go modules + per-module `Makefile`                                                                    |
| `cowork-chat`          | TypeScript (NestJS)                      | npm                                                                                                   |
| `cowork-promotion`     | TypeScript (static site, no framework)   | npm + `scripts/build.mjs`                                                                             |
| `cowork-monitoring`    | —                                        | Prometheus/Grafana/Loki/Alertmanager config only                                                      |

`settings.gradle.kts` includes only the seven JVM modules; a directory absent from it is not a Gradle project. `scripts/bump.sh` (`make bump`) stamps the release version into every build file — add a new module there when you create one.

## Data Stores

- **MySQL** — most services. **PostgreSQL** — `cowork-preference` only. **MongoDB** — `cowork-chat`, `cowork-voice`.
- Also in the stack: Redis, Kafka, Elasticsearch (`cowork-chat` search), Vault.

## Identity & Gateway

- `cowork-gateway` is the only place that validates JWT (`cowork-gateway/src/main/kotlin/com/cowork/gateway/security/`). Downstream services must NOT parse or validate JWT — trust the Gateway-forwarded headers instead:
  - `X-User-Id`: `Long`
  - `X-User-Role`: `ADMIN` | `MEMBER`
- Configure CORS at `cowork-gateway` (`cowork-config/src/main/resources/configs/cowork-gateway-*.yml`), not per service.
- **Documented exception:** `cowork-chat`'s WebSocket gateway (`src/chat/chat.gateway.ts`) verifies the handshake token itself and sets its own CORS, because a WS upgrade does not pass through the Gateway's header-mutating filter. Do not copy this pattern into HTTP routes.
- Don't expose service ports outside the Docker network in production-oriented config; all external traffic enters through the Gateway.

## Database Conventions

- Never modify a committed migration (`V{n}__*.sql`); add a new version instead.
- Relational tables use the `tb_` prefix, with `idx_tb_{table}_{column}` / `uq_tb_{table}_{column}` / `fk_tb_{table}_{target}` constraint names. Pre-existing exceptions — `cowork-preference`'s four tables and the third-party `shedlock` table — stay as they are; don't add new ones.
- Never create real cross-service foreign keys. Store another service's ID as a plain column and document the source in its `COMMENT`:

  ```sql
  team_id BIGINT NOT NULL COMMENT 'cowork-team의 tb_teams.id'
  ```

- The `V{n}__` naming matters beyond Flyway: the Go services run a hand-rolled migration runner (`internal/infra/mysql/migrate.go`) that parses the same filename pattern from `/app/db/migration`.
- MongoDB-backed services don't use migrations — keep schema definitions alongside the code (`cowork-chat/src/chat/schema/*.schema.ts`).

## Configuration

Shared config is served by `cowork-config`, which is both the Config Server and the Eureka server, and which holds every service's `configs/cowork-{service}-{env}.yml`. Local-only overrides go in `application-local.yml` (gitignored). Startup order is encoded in `docker-compose.yml`'s `depends_on` — `cowork-config` first, then `cowork-gateway`, then the rest in any order.

---

File-type-scoped rules (SQL migrations, Spring config, JVM sources) live in `.claude/rules/`. Kotlin/Java code style lives in `CONTRIBUTING.md`.
