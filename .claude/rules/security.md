---
paths:
  - "**/*.java"
  - "**/application*.yml"
  - "**/application*.yaml"
  - "**/bootstrap*.yml"
  - "**/bootstrap*.yaml"
---

# Security Rules

- Never hardcode sensitive values (DB credentials, JWT secrets, API keys). Always inject via environment variables.
  ```yaml
  # correct
  password: ${DB_PASSWORD}

  # wrong — never commit
  password: mypassword123
  ```
- Never parse JWT in downstream services. Gateway validates the token and forwards user info via headers.
- Downstream services must trust `X-User-Id` (Long) and `X-User-Role` (String: ADMIN | MEMBER) headers from Gateway.
- Block direct calls that bypass Gateway in production environments.
