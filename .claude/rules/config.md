---
paths:
  - "**/application*.yml"
  - "**/application*.yaml"
  - "**/bootstrap*.yml"
  - "**/bootstrap*.yaml"
---

# Configuration Rules

- Sensitive values (DB credentials, JWT secrets, API keys): inject via environment variables or GitHub Secrets — never inline.

  ```yaml
  # correct
  password: ${DB_PASSWORD}
  ```

- General service config belongs in the `cowork-config` Config Server, not in the service's own resources.
- Local-only overrides go in `application-local.yml`, which is gitignored.

## Required Flyway Config for Spring Boot Services

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
```
