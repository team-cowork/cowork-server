---
paths:
  - "**/application*.yml"
  - "**/application*.yaml"
  - "**/bootstrap*.yml"
  - "**/bootstrap*.yaml"
  - "cowork-config/src/main/resources/configs/**/*.yml"
---

# Configuration Rules

- Sensitive values (DB credentials, JWT secrets, API keys): inject via environment variables or GitHub Secrets — never inline.

  ```yaml
  # correct
  password: ${DB_PASSWORD}
  ```

- General service config belongs in the `cowork-config` Config Server, not in the service's own resources.
- Local-only overrides go in `application-local.yml`, which is gitignored.

## Profiles

Only `local` and `prod` exist. Every service needs both `configs/cowork-{service}-local.yml` and `configs/cowork-{service}-prod.yml`; the Config Server serves nothing for an undefined profile.

## `${VAR}` Placeholders in `configs/*.yml`

The Config Server serves raw strings — it does not resolve placeholders. Only the client resolves them, and not every client can:

| Client | Resolves `${VAR}`? | How to supply a value |
|---|---|---|
| Spring services (`gateway`, `team`, `project`, `roadmap`, `channel`) | yes | `${VAR}` or Vault |
| `cowork-preference` (Vert.x) | yes, against its own container env | `${VAR}` or Vault |
| `cowork-authorization`, `cowork-notification`, `cowork-voice` (Go), `cowork-chat` (NestJS), `cowork-user` (Elixir) | **no** | literal value + Vault, or `spring.cloud.config.server.overrides` |

For a non-resolving client, a `${VAR}` that nothing overrides reaches the app as the literal string `"${VAR}"`. Write a literal default and let Vault or `overrides` take precedence instead.

Precedence in the served response is `overrides` > Vault > native `configs/`. `overrides` can only replace a top-level key by exact name, so it cannot reach a nested key such as `db.dsn` — use Vault for those.

## Required Flyway Config for Spring Boot Services

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
```
