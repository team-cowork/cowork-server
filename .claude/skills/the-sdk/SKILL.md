---
name: the-sdk
description: Usage guide for the-sdk common library — HTTP request/response logging with UUID Log-ID, CommonApiResponse wrapping, ExpectedException handling, and Swagger auto-configuration.
---

# the-sdk Usage Guide

`io.github.themoment-team:the-sdk:2.0` from Maven Central.
Controlled via `sdk.*` settings in `application.yml`.

For MVC auto-configuration, provide Spring Boot 4 version management and `spring-boot-starter-web`.
Kotlin consumers also declare `tools.jackson.module:jackson-module-kotlin` (Jackson 3).

## Features

- **Logging**: Assigns a UUID `Log-ID` to each HTTP request/response pair.
- **Response Wrapper**: When enabled, wraps return values in `CommonApiResponse`. Use `success()` / `created()` / `error()` factories.
- **Exception Handler**: When enabled, handles `ExpectedException(message, HttpStatus)` as a standard error response.
- **Swagger**: Auto-configures OpenAPI. Documented paths follow `sdk.swagger.paths-to-match` (default `/v1/**`).

See the [2.0 README](https://github.com/themoment-team/the-sdk/blob/2.0/README.adoc) for detailed response and Swagger rules.
