---
paths:
  - "cowork-github/src/**/*.ts"
---

# GitHub App

- Read environment variables only through `AppConfigService`. Only the Config Server bootstrap in `src/config/` reads `process.env` directly, because it runs before Nest creates the module.
- Register each new feature as its own Nest module imported by `GithubModule`.
- Supply `GITHUB_PRIVATE_KEY` as a base64-encoded PEM string. Never commit `.pem` files or keep a decoded key after local testing.
- Kafka consumers commit and skip invalid payloads and GitHub 4xx (`GithubClientError`) failures. Other failures exit the process so the container restarts and the uncommitted message is retried.
- Installation access tokens are cached in Redis with a TTL below their one-hour expiry (default 3300s). This reuse is intentional and follows GitHub's guidance.
- The `owner → installationId` map is an intentional in-memory L1 cache in front of Redis. It is bounded by the number of organization installations.
- Installations are resolved through `/orgs/{owner}/installation`. Only organization accounts are supported.
