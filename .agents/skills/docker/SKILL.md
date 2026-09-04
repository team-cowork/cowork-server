---
name: docker
description: Dockerfile and docker-compose authoring guide — multi-stage builds, layer caching, security best practices, and compose service wiring.
---

# Docker Guide

## Build Context and Toolchain

Read the target service's `local.dockerfile`, `prod.dockerfile`, and build manifest before editing. Toolchain versions and build ownership are module-specific; do not reuse a standalone Java 21/Gradle template across this monorepo.

- Gradle modules commonly build from repository-root context and need the root settings, version catalog, and module files.
- `cowork-project` builds with Maven from `pom.xml`.
- `cowork-preference` builds with the Kotlin Toolchain/Amper from `module.yaml`.
- Go, npm, and Mix services use their own build stages and lockfiles.

## Layer Caching and Runtime

Copy dependency manifests/wrappers before source where the build supports caching. Keep compilation tools in the build stage and copy only required runtime output to the final image. Exclude local secrets, caches, logs, and build artifacts with the applicable `.dockerignore`.

Use an explicit version tag or digest instead of `latest`, and run as a non-root user where supported. Create that user with commands appropriate to the base distribution; `USER` is a Dockerfile instruction, not a shell command inside `RUN`.

## Compose Wiring

- `docker-compose.override.yml` selects local Dockerfiles when Compose is invoked normally. Use the existing explicit `-f` combination for production workflows.
- General application settings come from Config Server; application secrets come from Vault. Bootstrap values and file credentials follow `docs/configuration.md`.
- Reference required infrastructure credentials with `${VARIABLE:?message}` in production-oriented examples instead of committing literal passwords.
- Production external HTTP traffic enters through Gateway. Do not publish downstream service, database, or ops ports to the host; local diagnostic port mappings are a separate concern.
- Model startup dependencies with `service_healthy` or `service_completed_successfully` for init jobs. Projection-dependent services must advertise readiness only after their required streams are ready.
- Use healthcheck commands available in the runtime image. Distroless images do not provide a shell, `curl`, or `wget` by default.

## Verification

Validate the intended Compose merge with `docker compose config --quiet` (or the corresponding explicit `-f` files). Build only affected images when needed. Do not start, replace, or delete running environments merely to validate documentation.
