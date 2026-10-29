---
paths:
  - "**/{application,bootstrap}*.{yml,yaml}"
  - "cowork-*/internal/config/**"
  - "cowork-chat/src/common/config/**"
  - "cowork-github/src/config/**"
  - "cowork-preference/src/main/kotlin/com/cowork/preference/{config/**,CoworkPreferenceMain.kt}"
  - "cowork-user/src/Config/**"
  - "deploy/{production/**,ansible/**,nomad/**,tofu/**,runtime/**}"
---

# Configuration

- Each application owns its defaults and environment-variable bindings. Spring applications use their production profile; other runtimes read their native configuration directly. Do not add Config Server or Eureka clients.
- Keep VM placement, internal endpoints, resource limits, volumes, routes, and access policies in Git. Nomad jobs manage workloads, Ansible manages host software and TLS, and OpenTofu manages platform resources.
- GitHub Variables and Secrets supply declared application settings and credentials. CD publishes versioned values to Vault; each Nomad workload reads only its own path using workload identity. Do not duplicate these values in runtime JSON or production profiles. Shared URLs, origins, database identities, and bucket settings must reach every application, data, and monitoring consumer from the same GitHub input. Keep secret payloads and bootstrap credentials outside Git.
- Declare application inputs in `deploy/production/services.json` and bind the corresponding GitHub inputs in `.github/workflows/cowork-prod-cd.yml`. Keep shared credentials in one source and module-only credentials in their module environment.
- Use the same canonical environment names across runtimes. Keep driver-specific conversion, such as Kafka JAAS or MongoDB connection strings, in the application's existing configuration code rather than CD wrappers.
- Use explicit Envoy loopback endpoints for application connections. Consul manages discovery and service-mesh authentication; applications do not receive Consul management credentials.
- Normal configuration updates preserve persistent data; do not perform destructive initialization on releases.
