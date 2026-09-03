---
name: kotlin-spring-arch
description: Architecture reference for this project — Controller/Service/Repository layer responsibilities, @Transactional strategy (readOnly optimization, N+1 prevention), ExpectedException usage (no subclasses), and Entity↔DTO conversion patterns.
---

# Kotlin + Spring Boot Architecture Guide

Apply this guide to team/channel/project Spring MVC/JPA code. Read `CONTRIBUTING.md` for naming, annotation targets, and examples. Gateway is reactive; preference uses Vert.x and does not use Spring/JPA annotations.

## Layer Structure

### Controller
- Role: Request validation, route handling, HTTP response; entity-to-DTO conversion belongs to the service or DTO factory
- Annotations: `@RestController`, `@RequestMapping`
- Validation: `@Valid`, `@Validated`
- Response: Return DTOs directly; team enables SDK wrapping, while channel/project disable it and use the Gateway response contract

Preserve explicit no-content statuses and configured streaming/wrapper exclusions. Do not manually add a data envelope just because the SDK dependency is present.

### Service
- Role: Business logic, resource authorization, transaction management, entity-to-DTO conversion
- Pattern: interface + implementation
- Transaction (method-level):
  - Read: `@Transactional(readOnly = true)`
  - Write: `@Transactional`
- Dependencies: Inject Repository via constructor injection

### Repository
- Role: Data access
- JPA: Extend `JpaRepository`
- Queries: Use derived JPA methods or parameterized JPQL/native SQL. QueryDSL is not configured in the current modules.
- Avoid N+1: Fetch Join, `@EntityGraph`

## Transaction Strategy

### Read-only Optimization
```kotlin
@Transactional(readOnly = true)
fun findTeams(): List<TeamResponse> {
    return repository.findAll()
        .map { TeamResponse.of(it) }
}
```

### N+1 Problem Resolution
```kotlin
// ❌ N+1 occurs
repository.findAll() // 1 query
entity.relatedEntity // N queries

// ✅ Fetch Join
@Query("SELECT e FROM Entity e JOIN FETCH e.relatedEntity")
fun findAllWithRelated(): List<Entity>
```

## Exception Handling

### Use ExpectedException Directly
```kotlin
val team = teamRepository.findById(id).orElseThrow {
    ExpectedException("팀을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
}
```

Do not create custom exception subclasses extending `ExpectedException`. Messages are Korean, end with a period, and contain no dynamic IDs or names.

### Global Handler
Follow the module-local `AdditionalExceptionHandler`/`GlobalExceptionHandler` and `sdk.exception` settings. There is no shared monorepo exception-handler module.

## DTO Conversion Pattern

Use the surrounding module's DTO factories, commonly `TeamResponse.of(team)` or `ProjectResDto.of(project)`. Keep public return types explicit and avoid introducing a second naming family just for a new endpoint.

## Inter-service State

The domain owner writes durable state and its outbox record in one local transaction. Read another service's state through an idempotent local projection. Follow `.claude/rules/kafka-projections.md` for request-scoped HTTP exceptions and readiness; an immediate result or generated ID does not justify a new internal HTTP call.
