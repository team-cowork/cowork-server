# Cowork Server

## Overview

Team collaboration platform backend monorepo based on microservices.

- Core stack: Spring Boot, Spring Cloud Gateway, Eureka, OpenFeign, Kafka, Flyway, MySQL, MongoDB
- Main services: `cowork-config`, `cowork-gateway`, `cowork-authorization`, `cowork-user`, `cowork-team`, `cowork-project`, `cowork-channel`, `cowork-chat`, `cowork-voice`, `cowork-preference`, `cowork-notification`

## Agent Working Rules

Apply the following rules whenever editing code, configuration, or database artifacts in this repository.

## Security

- Never hardcode secrets such as DB credentials, JWT secrets, or API keys.
- Always inject sensitive values via environment variables or secret management.
- In YAML config, prefer placeholders such as `${DB_PASSWORD}` instead of literal values.
- Do not implement JWT parsing or validation in downstream services.
- The Gateway is the single place that validates JWT and forwards trusted identity headers.
- Downstream services must trust these Gateway-provided headers:
  - `X-User-Id`: `Long`
  - `X-User-Role`: `String` with values `ADMIN` or `MEMBER`
- In production-oriented code or config, prevent direct service access that bypasses the Gateway.

## Database

- Never modify an already committed Flyway migration file named `V{n}__*.sql`.
- If schema changes are needed, add a new Flyway migration version instead.
- Flyway migration files must follow this naming format:
  - `V{n}__{snake_case_description}.sql`
  - Example: `V2__add_github_id.sql`
- All relational table names must use the `tb_` prefix.
- Use these database naming conventions:
  - Index: `idx_tb_{table}_{column}`
  - Unique key: `uq_tb_{table}_{column}`
  - FK-like column name: `fk_tb_{table}_{target}`
- Never create real foreign key constraints across services.
- When referencing another service's resource, store the ID as a normal column and document the source in the column `COMMENT`.
- Example:

```sql
team_id BIGINT NOT NULL COMMENT 'cowork-team의 tb_teams.id'
```

- MongoDB-based services such as `cowork-chat` and `cowork-voice` do not use Flyway.
- For MongoDB services, manage schema-related definitions inside each service's `schema/` directory.

## Configuration

- General shared service configuration must be managed through `cowork-config` Config Server.
- Local-only overrides belong in `application-local.yml`, and that file must stay ignored by Git.
- For Spring Boot services using relational DB migrations, ensure Flyway is enabled with:

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
```

## Service Startup Order

Start services in this order when bringing up the platform locally or validating environment setup:

1. `cowork-config` because it provides Eureka and Config Server
2. `cowork-gateway` after Config Server is ready
3. Business services such as `authorization`, `user`, `team`, `project`, and `channel` in any order

## Scope Notes

- These rules are especially relevant when editing:
  - Java sources
  - Spring `application*.yml` and `bootstrap*.yml`
  - Flyway SQL files under `db/migration/`
  - JPA entities and repositories
- When there is a conflict between convenience and these rules, follow these rules.
