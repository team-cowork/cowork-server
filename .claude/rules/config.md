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

The supported deployment profiles are `local` and `prod`. Gateway and every backend business service need both `configs/cowork-{service}-local.yml` and `configs/cowork-{service}-prod.yml`; Config Server itself uses its bootstrap `application.yml`. Monitoring and the static promotion site are not Config clients. Do not assume an unsupported profile returns an empty response: common Vault properties and server overrides are not proof that a service profile exists.

## `${VAR}` Placeholders in `configs/*.yml`

The Config Server serves raw strings — it does not resolve placeholders. Only the client resolves them, and not every client can:

| Client | Resolves `${VAR}`? | How to supply a value |
|---|---|---|
| Spring services (`gateway`, `team`, `project`, `roadmap`, `channel`) | yes | `${VAR}` or Vault |
| `cowork-preference` (Vert.x) | yes, against its own container env | `${VAR}` or Vault |
| `cowork-authorization`, `cowork-notification`, `cowork-voice` (Go), `cowork-chat` (NestJS), `cowork-user` (Elixir) | **no** | literal value + Vault, or `spring.cloud.config.server.overrides` |

For a non-resolving client, a `${VAR}` that nothing overrides reaches the app as the literal string `"${VAR}"`. Write a literal default and let Vault or `overrides` take precedence instead.

Precedence in the served response is `overrides` > Vault > native `configs/`. Custom clients merge the flat keys in `propertySources`; replacements must match the exact key the client reads. Do not pass a nested YAML object as if it were a scalar override. For the existing notification `db.dsn` and preference `preference.db.*` secrets, use their exact dotted Vault keys as documented in `docs/configuration.md`.

## Required Flyway Config for Relational Spring Business Services

Apply this to channel/team/project/roadmap, not to Gateway or Config Server. Roadmap additionally configures a JDBC Flyway URL alongside its runtime R2DBC connection.

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
```
