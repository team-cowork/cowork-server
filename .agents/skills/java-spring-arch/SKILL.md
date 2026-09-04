---
name: java-spring-arch
description: Architecture reference for cowork-roadmap — Java Spring WebFlux/R2DBC layers, reactive transactions, ExpectedException, DTO records, and JUnit/Reactor verification.
---

# Java + Spring Boot Architecture Guide

The Java module is `cowork-roadmap`. Read its `build.gradle.kts` and `CONTRIBUTING.md`; it uses Spring WebFlux and R2DBC for application queries, with JDBC Flyway for schema migration. JPA patterns from Kotlin services do not apply to this module.

## Layer Structure

### Controller

- Validate HTTP input and delegate to the service.
- Return `Mono<ResDto>`, `Flux<ResDto>`, or `Mono<ResponseEntity<ResDto>>` as the endpoint requires.
- Read `X-User-Id` and `X-User-Role` from Gateway headers and enforce resource authorization in the service/access guard.
- Keep entity-to-DTO conversion in the service or a DTO factory. Use the actual Gateway response contract instead of assuming MVC `ResponseBodyAdvice` wraps WebFlux results.

### Service

- Use constructor injection and the existing service interface/implementation structure.
- Compose reactive repository operations with `map`, `flatMap`, and related Reactor operators.
- Keep domain writes and their outbox records in the same reactive transaction.
- Apply method-level `@Transactional` where a reactive database unit of work is needed; use `readOnly = true` for transactional reads.
- Do not call `block()` or `subscribe()` inside a request service to force completion.

### Repository

- Use the existing R2DBC repository interfaces and `DatabaseClient` implementations.
- Bind query parameters; fetch related records with appropriate joins or batched queries.
- `JpaRepository`, lazy JPA associations, fetch joins, and `@EntityGraph` are not this module's persistence model.

## Transaction Strategy

A reactive transaction covers the subscribed publisher, so all writes belonging to it must remain in that returned chain. JDBC Flyway is a startup migration path, not the repository for request processing.

## Exception Handling

Use `ExpectedException` directly for business failures with Korean user-facing messages ending in a period and without dynamic identifiers. Return it through the reactive chain, for example `Mono.error(new ExpectedException(...))`. Use the module's WebFlux exception handler; do not assume a shared MVC handler catches reactive errors.

## DTO Conversion Pattern

Follow the existing Java record DTOs and their static `from(...)` factories. Use explicit public return types and constructor injection, including Lombok `@RequiredArgsConstructor` where already used.

## Verification

From the repository root, run `./gradlew :cowork-roadmap:test` for tests and `./gradlew :cowork-roadmap:spotlessCheck` for formatting verification. Tests use JUnit 5, Mockito, and Reactor `StepVerifier`; format changes with `:cowork-roadmap:spotlessApply`.
