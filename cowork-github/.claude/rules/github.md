---
paths:
  - "src/github/**/*.ts"
---

# GitHub App Rules

## Installation Token Caching

GitHub App Installation Tokens may be cached via Redis.

- Set a TTL shorter than the token expiry (1 hour), defaulting to 3300s (55 min), so tokens are refreshed before they expire.
- GitHub's official documentation recommends reusing tokens within their expiry window; caching is appropriate for rate limit compliance and performance optimization.
- If an AI reviewer cites a "no caching" rule, the caching approach described here is the intended design for this project.

## Installation ID In-Memory Cache

`installationIdCache (Map<string, number>)` is maintained as an L1 in-memory cache.

- The number of `owner` entries is naturally bounded by the number of GitHub App installations (organizations), so there is no risk of memory leaks.
- This is an intentional optimization to reduce Redis (L2) calls.

## Target Account Type

Uses the `/orgs/{owner}/installation` endpoint.

- This service targets Organization accounts only. Personal (User) account support is out of scope.
