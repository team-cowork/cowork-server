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
| `cowork-authorization` | Go (Gin)                                 | Go modules + per-module `Makefile`                                                                    |
| `cowork-notification`  | Go (Chi)                                 | Go modules + per-module `Makefile`                                                                    |
| `cowork-voice`         | Go (Chi, LiveKit)                        | Go modules + per-module `Makefile`                                                                    |
| `cowork-chat`          | TypeScript (NestJS)                      | npm                                                                                                   |
| `cowork-promotion`     | TypeScript (static site, no framework)   | npm + `scripts/build.mjs`                                                                             |
| `cowork-monitoring`    | —                                        | Prometheus/Grafana/Loki/Alertmanager config only                                                      |

`settings.gradle.kts` includes only the seven JVM modules; a directory absent from it is not a Gradle project. `scripts/bump.sh` (`make bump`) updates the release-version locations it lists — register a new module's version there when you create one. Formatting and test tasks are build-system-specific; see `CONTRIBUTING.md` before assuming a root Gradle command covers every module.

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

## Inter-service Communication

- Keep each state mutation with its domain owner; a public API's location does not transfer ownership. Preserve cross-service consistency through a transactional outbox and idempotent Kafka projection.
- An immediate result, generated ID, or validation error alone does not justify internal HTTP; document the rare request-scoped exception. Only explicitly exempted GitHub App APIs are on hold.
- Follow `.claude/rules/kafka-projections.md` for command/event contracts, ordering, checkpoints, and readiness.

## Database Conventions

- Never modify a committed migration (`V{n}__*.sql`); add a new version instead.
- Relational tables use the `tb_` prefix, with `idx_tb_{table}_{column}` / `uq_tb_{table}_{column}` / `fk_tb_{table}_{target}` constraint names. Pre-existing exceptions — `cowork-preference`'s `account_channel_notification`, `resource_setting`, `project_role_definition`, and `account_project_role` tables and the third-party `shedlock` table — stay as they are; don't add new ones.
- Never create real cross-service foreign keys. Store another service's ID as a plain column and document the source in its `COMMENT`:

  ```sql
  team_id BIGINT NOT NULL COMMENT 'cowork-team의 tb_teams.id'
  ```

- The `V{n}__` naming matters beyond Flyway: the MySQL-backed Go services (`cowork-authorization`, `cowork-notification`) run a custom migration runner (`internal/infra/mysql/migrate.go`) that parses the same filename pattern. It uses `/app/db/migration` in containers and also supports local migration directories.
- MongoDB-backed services do not use the SQL/Flyway migration runner. Keep document schemas and index setup alongside the code (`cowork-chat/src/chat/schema/*.schema.ts` and `cowork-voice/internal/infra/mongo/`).

## Configuration

Shared service config is served by `cowork-config`, which is both the Config Server and the Eureka server. Its `configs/cowork-{service}-{env}.yml` files hold runtime service settings; bootstrap/client setup and framework-specific wiring also exist in each service. Spring local-only overrides go in `application-local.yml` (gitignored); other runtimes follow their own configuration loaders. See `.claude/rules/config.md` before using `${VAR}` placeholders in Config Server files, since client support differs.

Follow `docker-compose.yml`'s actual `depends_on` health and initialization conditions: Config Server and infrastructure readiness precede application startup; `cowork-user` also waits for a healthy `cowork-authorization` presence source. Services are not globally sequenced behind the Gateway. Projection consumers additionally gate readiness on replay progress and complete snapshots, as defined in `.claude/rules/kafka-projections.md`.

### Agent Tooling

Project hooks require Bash and `jq`. Their command guards reject destructive disk/root operations and direct download-to-shell commands; command logging records supported shell calls in `.claude/command.log` or `.codex/command.log`, so do not place secrets in command arguments. Format hooks attempt root KtLint/Spotless only for recognized edit events; their targets and limitations are documented in `CONTRIBUTING.md`.

Secret-guard scripts check recognized write payloads for common credential patterns and skip `.env` paths; this is not permission to commit secrets. Claude's current `PreToolUse` matcher is `Bash`, so it does not dispatch those scripts for `Edit`/`Write`. Codex hook coverage likewise depends on the emitted tool name and payload. Review changes explicitly rather than assuming every tool write was checked.

---

File-type-scoped rules (SQL migrations, Spring config, JVM sources) live in `.claude/rules/`. Kotlin/Java code style lives in `CONTRIBUTING.md`.
