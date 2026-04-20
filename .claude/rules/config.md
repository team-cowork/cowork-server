---
paths:
  - "**/application*.yml"
  - "**/application*.yaml"
  - "**/bootstrap*.yml"
---

# Configuration Rules

- Sensitive values (DB credentials, JWT secrets): inject via environment variables or GitHub Secrets.
- General service config: manage via `cowork-config` Config Server.
- Local-only config: `application-local.yml` (must be in `.gitignore`).

## Required Flyway Config for All Spring Boot Services

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
```

## Service Startup Order

```
1. cowork-config   (Eureka + Config Server — start first)
2. cowork-gateway  (start after Config Server is ready)
3. Business services  (authorization, user, team, project, channel — order doesn't matter)
```
