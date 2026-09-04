---
paths:
  - "cowork-*/src/**"
  - "cowork-*/internal/**"
  - "cowork-user/lib/**"
---

# Service Boundaries

- Treat consistency and recoverability as the primary reasons for Kafka-backed state replication; reduced request-path latency and coupling are secondary benefits.
- Define one authoritative owner for durable state. A public API's location does not transfer ownership; do not relocate an aggregate merely to remove an HTTP call. Keep the owner's mutation and outbox write in the same local transaction, then replicate the resulting state through Kafka when another service needs it.
- Do not add internal REST/Feign calls for another service's durable state. Send an asynchronous command to the existing owner for mutations and consume an idempotent local projection for reads; an immediate result, generated ID, or validation error alone is not an HTTP exception.
- Keep synchronous internal HTTP only for an inherently request-scoped operation that cannot be reconstructed or reconciled from durable state, and document that reason at the client. Only GitHub App APIs explicitly marked as temporarily exempt are on hold; calls to external providers are not inter-service calls.
- Do not implement synchronous Kafka request/reply as a substitute for REST. Use commands/events for asynchronous workflows and local projections for reads.
