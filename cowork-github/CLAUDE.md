# cowork-github

## Overview

Microservice responsible for creating GitHub issues via GitHub App authentication.
Part of the `cowork-server` ecosystem, it consumes events from `cowork-chat` via Kafka and calls the GitHub API.

- Core stack: NestJS, TypeScript, Kafka, Axios
- GitHub auth: GitHub App (RS256 JWT → Installation Token)

## Architecture

```
cowork-chat (NestJS)
    → Kafka produce (topic: github.issue.create)
    → cowork-github (NestJS) consume
    → GitHub API
```

External client requests enter through `cowork-gateway`.
Internal service-to-service events are handled asynchronously via Kafka.

## Project Structure

```
src/
├── github/
│   ├── auth/
│   │   └── github-auth.service.ts   # JWT generation + Installation Token exchange
│   ├── client/
│   │   └── github-api.client.ts     # GitHub API HTTP calls
│   ├── issue/
│   │   └── issue.service.ts         # Issue creation business logic
│   ├── dto/
│   │   └── create-issue.dto.ts      # Kafka event payload DTO
│   ├── github.controller.ts         # Kafka Consumer (MessagePattern)
│   └── github.module.ts
├── app.module.ts
└── main.ts
```

## Environment Variables (.env)

```
# GitHub App
GITHUB_APP_ID=           # GitHub App ID (numeric)
GITHUB_PRIVATE_KEY_PATH= # Path to .pem file (e.g. ./private-key.pem)

# Kafka
KAFKA_BROKER=localhost:9094

# Server
PORT=3001
```

## Kafka

- Broker: `kafka:9092` (internal) / `localhost:9094` (local dev)
- Auto topic creation is enabled (`KAFKA_AUTO_CREATE_TOPICS_ENABLE: true`)
- Kafka UI: `http://localhost:8090`

### Topics

| Topic | Direction | Description |
|-------|-----------|-------------|
| `github.issue.create` | consume | cowork-chat → cowork-github |

### Event Payload (github.issue.create)

```json
{
  "installationId": 123456,
  "owner": "team-cowork",
  "repo": "my-repo",
  "title": "Issue title",
  "body": "Issue body"
}
```

## GitHub App Auth Flow

```
1. Generate JWT using private-key.pem (RS256, expires in 10 minutes)
   payload: { iat: now - 60, exp: now + 600, iss: APP_ID }

2. Exchange JWT for Installation Token
   POST https://api.github.com/app/installations/{installationId}/access_tokens
   Authorization: Bearer {JWT}

3. Call GitHub API with Installation Token
   POST https://api.github.com/repos/{owner}/{repo}/issues
   Authorization: Bearer {installation_token}
```

## Agent Working Rules

### Security

- Never use Personal Access Tokens — GitHub App authentication only
- Never commit secrets (`.env`, `.pem`) to git
- Always include `.env` and `private-key.pem` in `.gitignore`
- Always include `X-GitHub-Api-Version: 2022-11-28` header in GitHub API calls

### Code

- All GitHub-related logic must live inside the `github/` module
- Access environment variables only via `ConfigService` from `@nestjs/config`
- Fetch a new Installation Token on every request — no caching
- Handle GitHub API errors in `github-api.client.ts`
    - 401 → Authentication failure
    - 403 → Permission denied (App not installed on target repo)
    - 404 → Repository not found

### Kafka

- Consumer group ID: `cowork-github`
- Topic naming convention: `{service}.{resource}.{action}` (e.g. `github.issue.create`)
- On message processing failure, log the error and consider dead letter queue handling

## Service Startup Order

Follow the startup order defined in `cowork-server`:

1. `cowork-config`
2. `cowork-gateway`
3. Business services (including `cowork-github`)

Ensure Kafka is healthy before starting `cowork-github`.

## Development

```bash
# Start dev server
npm run start:dev

# Start Kafka via cowork-server docker-compose
cd /path/to/cowork-server
docker-compose up kafka
```