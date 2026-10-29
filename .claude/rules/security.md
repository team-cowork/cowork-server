---
paths:
  - "cowork-*/**/*.{kt,java,go,ts,cs}"
  - "deploy/{ansible/**,nomad/**,production/**,runtime/**,tofu/**}"
---

# Security

- Validate Cowork access JWTs at Gateway. Downstream HTTP handlers use its `X-User-Id` and `X-User-Role` headers and enforce resource-level authorization. Chat's WebSocket handshake is the only access-JWT validation exception; do not extend it to HTTP routes.
- Gateway owns HTTP CORS; Chat owns WebSocket CORS.
- Public application APIs enter through Gateway. Keep raw backend listeners inside Nomad allocation networks and expose only declared Consul mesh connections. Preserve explicitly configured ingress for signed external webhooks and LiveKit media.
- Keep external HTTPS termination and existing VM addresses unchanged unless the user authorizes infrastructure changes. Restrict host ingress to the declared proxy and inventory peers.
- Enable Nomad and Consul ACLs, TLS for their APIs, mutual TLS for internal RPC, and workload identity for Consul and Vault. Never give applications platform management tokens or private CA keys.
- Kafka uses native SASL_SSL with service-specific SCRAM credentials and certificate validation. HTTP and database mesh links use Consul intentions with default-deny access.
- Run only trusted main-branch deployment work on the protected self-hosted runner. Builds and pull-request verification run on GitHub-hosted workers; credentials, Terraform state, and bootstrap PKI stay outside the repository.
