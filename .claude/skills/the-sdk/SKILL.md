---
name: the-sdk
description: Usage guide for the-sdk common library — HTTP request/response logging with UUID Log-ID, CommonApiResponse wrapping, ExpectedException handling, and Swagger auto-configuration.
---

# the-sdk Usage Guide

The Spring business modules depend on `com.github.themoment-team:the-sdk`; the version is declared in `gradle/libs.versions.toml` and, for project, `pom.xml`. It is not a shared runtime for Go, Elixir, NestJS, or Vert.x.

Read the target module's `application.yml`, Config Server profile, and local handlers before relying on any `sdk.*` feature.

## Features and Actual Configuration

- **Logging:** SDK logging can attach a UUID `Log-ID` to HTTP request/response logs. Respect `sdk.logging.enabled` and `not-logging-urls`; do not assume the MVC feature handles WebFlux or every runtime.
- **Response wrapping:** Return data DTOs directly. Team configures SDK wrapping, while channel/project set `sdk.response.enabled: false` and rely on the Gateway for eligible public JSON responses. Preserve no-content and streaming responses.
- **Exception handling:** Use `ExpectedException(message, HttpStatus)` directly for Spring business failures, with the message convention in `CONTRIBUTING.md`. Channel/project disable the SDK exception handler and provide local handlers; roadmap has a WebFlux handler. Dependency presence alone does not establish the active handler.
- **OpenAPI:** Consult the module's `springdoc` and `sdk.swagger` settings. Current service paths include `/teams/**`, `/projects/**`, and `/channels/**`; documentation is not universally limited to `/v1/**`. The integrated public Swagger UI is served through Gateway.
