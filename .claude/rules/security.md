---
paths:
  - "**/*.kt"
  - "**/*.java"
  - "**/*.go"
  - "**/*.ts"
  - "**/*.ex"
  - "**/*.exs"
---

# Security Rules

- Never parse or validate JWT outside `cowork-gateway`. Read the caller's identity from the Gateway-forwarded `X-User-Id` (Long) and `X-User-Role` (`ADMIN` | `MEMBER`) headers instead.
  - The one documented exception is `cowork-chat`'s WebSocket handshake (`src/chat/chat.gateway.ts`); do not extend it to HTTP routes.
- Treat those headers as authenticated input, but still authorize: having a valid `X-User-Id` does not mean that user may touch the requested team, project, or channel.
- Don't add per-service HTTP CORS configuration — CORS is handled at the Gateway. The documented `cowork-chat` WebSocket handshake also owns its CORS configuration; do not extend that exception to HTTP routes.
