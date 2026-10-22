---
paths:
  - "cowork-*/{src,internal}/**"
  - "cowork-user/src/**"
---

# Service Boundaries

- Keep one authoritative owner for durable state. Do not move an aggregate merely to remove an HTTP call.
- Use asynchronous commands for another service's mutations and Kafka-backed local projections for reads. Do not add internal REST/Feign calls or synchronous Kafka request/reply for durable state.
- Synchronous internal HTTP is limited to request-scoped work that cannot be reconstructed or reconciled from durable state; explain that reason at the client. Calls to `cowork-github` are internal calls under this rule.
