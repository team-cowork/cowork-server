---
paths:
  - "cowork-*/**/*.{kt,java,go,ts,cs}"
  - "deploy/compose/*.yaml"
  - "deploy/prod/{slots/**,network/**,storage/firewall*,**/compose.yaml}"
  - "deploy/iac/{ansible/**,policy/**}"
---

# Security

- Validate Cowork access JWTs at Gateway. Downstream HTTP handlers use its `X-User-Id` and `X-User-Role` headers and enforce resource-level authorization. Chat's WebSocket handshake is the only access-JWT validation exception; do not extend it to HTTP routes.
- Gateway owns HTTP CORS; Chat owns WebSocket CORS.
- Public application APIs enter through Gateway. Bind downstream ports to private VM addresses and restrict peers with firewalls. Preserve explicitly configured ingress for signed external webhooks.
