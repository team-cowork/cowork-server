---
name: security-checklist
description: Verify security vulnerabilities — hardcoded secrets, SQL injection, JWT validation, API key masking, sensitive logging, and authorization checks. Run before merging any auth or API-related changes.
---

# Security Checklist

## Verification Items

### 1. Hardcoded Secrets
- [ ] No API Key, Secret, Password in code?
- [ ] Injecting secrets through Vault, environment variables, or secret volumes instead of committing values to config files?

Inspect the changed source and configuration files, including relevant Kotlin, Java, Go, TypeScript, and Elixir code. Use file-name-only searches first and redact any discovered values in the report; a matching key name is not proof of a leaked secret.

### 2. SQL Injection
- [ ] Binding parameters in every SQL/JPQL path with the runtime's query API?
- [ ] Not concatenating SQL strings directly?

### 3. Authentication Boundary
- [ ] Gateway validates access-token signatures, expiration, and required claims?
- [ ] Downstream HTTP handlers use Gateway-forwarded `X-User-Id`/`X-User-Role` and do not add JWT parsing or validation?
- [ ] Chat's documented WebSocket handshake exception validates its token and keeps that exception scoped to WebSocket authentication/CORS?
- [ ] Production networking prevents external callers from bypassing Gateway and forging identity headers?

Authorization issues tokens; do not confuse token issuance or external-provider credentials with downstream access-token validation.

### 4. API Key Security
- [ ] Masking API Key in responses?
- [ ] Encrypting API Key when storing?

### 5. Logging
- [ ] Not logging sensitive info (password, token, etc.)?
- [ ] Appropriate log level?

### 6. Resource Authorization
- [ ] Services verify the caller's team/project/channel membership, role, and resource ownership as required by the operation?
- [ ] HTTP, search, GraphQL, WebSocket, and administrative routes enforce their own applicable access contracts?
- [ ] Projection-dependent authorization returns `503` while required state is unavailable instead of a false `403` or partial success?
- [ ] Existing access guards/local projections are used without assuming `@PreAuthorize` is configured in every runtime?

## References

Read `AGENTS.md`, `CLAUDE.md`, `.claude/rules/security.md`, and `CONTRIBUTING.md`. Locate actual Gateway security and domain access guards with `rg --files cowork-gateway cowork-team cowork-channel cowork-project`; do not use unrelated API-key examples as the project's authorization model.
