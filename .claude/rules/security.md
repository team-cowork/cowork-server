---
paths:
  - "cowork-*/**/*.{kt,java,scala,go,ts,cs}"
  - "deploy/{ansible/**,nomad/**,production/**,runtime/**,tofu/**}"
---

# Security

- Gateway validates the access JWT for HTTP and the cookie-based access JWT for the `/ws/chat` WebSocket handshake. It strips external identity and role headers and forwards the authenticated subject as `X-User-Id`.
- Gateway limits agent access JWTs (`token_use=agent`) to their scope allowlist and rejects them for WebSockets. It forwards `X-Cowork-Agent: true` for them and strips client-sent copies, so services may rely on it to apply agent restrictions.
- Roadmap and Chat evaluate the requester's current Preference policies from their local Kafka projections. Require a synchronized account policy for global operations. Check team management in the resource's own team and retain active membership requirements. Chat projection status and rebuild each require a separate account permission.
- Gateway owns HTTP CORS; Chat owns WebSocket CORS.
- Public application APIs enter through Gateway. Keep raw backend listeners inside Nomad allocation networks and expose only declared Consul mesh connections. Preserve explicitly configured ingress for signed external webhooks and LiveKit media.
- Keep external HTTPS termination and existing VM addresses unchanged unless the user authorizes infrastructure changes. Restrict host ingress to the declared proxy and inventory peers.
- Enable Nomad and Consul ACLs, TLS for their APIs, mutual TLS for internal RPC, and workload identity for Consul and Vault. Never give applications platform management tokens or private CA keys.
- Kafka uses native SASL_SSL with service-specific SCRAM credentials and certificate validation. HTTP and database mesh links use Consul intentions with default-deny access.
- Run only trusted main-branch deployment work on the protected self-hosted runner. Builds and pull-request verification run on GitHub-hosted workers; credentials, Terraform state, and bootstrap PKI stay outside the repository.
