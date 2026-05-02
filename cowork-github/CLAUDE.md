# cowork-github

## Overview

A GitHub App backend service that listens to Kafka messages and automatically creates GitHub Issues when a user runs the `/이슈생성` command in the chat service.

## Runtime

- Framework: NestJS 11 (Kafka microservice + Express HTTP server)
- Language: TypeScript
- Default port: `3000`
- Dependencies: Kafka, Redis, GitHub App (JWT auth)

## Current Structure

```text
src/
├── config/
│   ├── app-config.service.ts   # type-safe env access
│   └── env.validation.ts       # Joi schema validation
├── github/
│   ├── auth/
│   │   └── github-auth.service.ts   # JWT issuance and installation token caching
│   ├── client/
│   │   └── github-api.client.ts     # GitHub REST API calls (axios)
│   ├── issue/
│   │   ├── issue.service.ts         # issue creation orchestration
│   │   ├── label.service.ts         # label inference and auto-creation
│   │   └── label.constants.ts       # default labels and keyword mapping
│   ├── dto/
│   │   └── create-issue.dto.ts      # Kafka payload DTO
│   ├── github.cache.ts              # Redis cache key helpers
│   ├── github.controller.ts         # Kafka consumer controller
│   ├── github.errors.ts             # custom error classes
│   └── github.module.ts
├── app.controller.ts
├── app.module.ts
└── main.ts

test/
├── support/
│   ├── jest-setup-e2e.ts
│   └── test-env.ts
├── app.e2e-spec.ts
└── jest-e2e.json
```

## Kafka Message Spec

**Topic**: `github.issue.create`

**Payload** (`CreateIssueDto`):

| Field       | Type       | Required | Description          |
|-------------|------------|----------|----------------------|
| `owner`     | `string`   | ✓        | GitHub org or user   |
| `repo`      | `string`   | ✓        | repository name      |
| `title`     | `string`   | ✓        | issue title          |
| `body`      | `string`   |          | issue body           |
| `labels`    | `string[]` |          | label list           |
| `assignees` | `string[]` |          | assignee list        |

## Error Handling

- **Invalid payload**: log and commit offset (skip)
- **GithubClientError (4xx)**: log and commit offset (skip)
- **Server error (5xx etc.)**: `process.exit(1)` — let the container restart for retry

## Environment Variables

```env
PORT=3000

KAFKA_BROKERS=localhost:9092
KAFKA_GROUP_ID=cowork-github-group

GITHUB_APP_ID=                          # GitHub App ID
GITHUB_PRIVATE_KEY=                     # PEM key encoded as base64

REDIS_HOST=localhost
REDIS_PORT=6379

# optional (defaults apply)
GITHUB_TOKEN_CACHE_TTL_SECONDS=3300
GITHUB_INSTALLATION_CACHE_TTL_SECONDS=86400
GITHUB_INSTALLATION_MEMORY_CACHE_MAX_SIZE=1000
GITHUB_ISSUE_MAX_RETRIES=3
```

## Development Rules

### Security

- Never commit `.env`, `.pem`, tokens, or any secrets.
- Supply `GITHUB_PRIVATE_KEY` as a base64-encoded string, not as a raw PEM file.
- Do not keep `private-key.pem` around after local testing.

### Code

- Access all env vars through `AppConfigService` — no direct `process.env` reads.
- Add new features as separate modules registered in `GithubModule`.
- Update related unit tests whenever code changes.

### Testing

- `npm test`: unit tests (`src/**/*.spec.ts`)
- `npm run test:e2e`: e2e tests
- `npm run build`: must always pass

## GitHub Actions

- `cowork-stage-ci.yml`: install, lint, build, test on PR/push targeting `develop`
- `cowork-prod-ci.yml`: install, lint, build, test on PR/push targeting `main`
- `cowork-prod-cd.yml`: creates a date-based tag and GitHub Release after `main` push
- `cowork-pr-cleanup.yml`: removes waiting labels from merged PRs
