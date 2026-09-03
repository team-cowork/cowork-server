---
name: kotest-guide
description: Kotest + MockK testing patterns for this project — Given/When/Then structure, mock creation, stubbing, argument capture, coroutine testing, and exception assertions. Reference when writing or reviewing test code.
---

# Kotest + MockK Testing Guide

Use this guide where the Kotlin module configures Kotest. Read `CONTRIBUTING.md` for the module's test runner and naming conventions. Keep existing JUnit tests in their configured framework; roadmap and preference do not use this Kotest structure.

## Given-When-Then

Use `DescribeSpec` with Korean `describe` / `context` / `it` names. Test one behavior per case and use related assertions or interaction checks as needed. The following uses the existing team service and access guard; imports are omitted.

```kotlin
class QueryTeamServiceTest : DescribeSpec({
    lateinit var accessGuard: TeamAccessGuard
    lateinit var service: QueryTeamService

    beforeEach {
        accessGuard = mockk()
        service = QueryTeamServiceImpl(accessGuard)
    }

    describe("QueryTeamService 클래스의") {
        describe("execute 메서드는") {
            context("팀 멤버가 아닌 경우") {
                it("접근을 거부하고 팀 정보를 조회하지 않는다") {
                    // Given
                    every { accessGuard.requireMemberExists(10L, 1L) } throws
                        ExpectedException("팀 멤버가 아닙니다.", HttpStatus.FORBIDDEN)

                    // When & Then
                    shouldThrow<ExpectedException> {
                        service.execute(userId = 1L, teamId = 10L)
                    }
                    verify(exactly = 0) { accessGuard.findTeamOrThrow(any()) }
                }
            }
        }
    }
})
```

## MockK Patterns

- Create typed mocks with `mockk<T>()` and rebuild shared fixtures in `beforeEach` when isolation requires it.
- Use `every { ... } returns value` for ordinary methods, `just Runs` for a `Unit` method, and `throws` for a failure path.
- Use `verify(exactly = n)` for interactions relevant to the behavior. Avoid asserting every implementation detail.
- Capture arguments with `slot<T>()`/`capture(slot)` when the contract depends on the object passed to a collaborator.
- Stub the signatures actually declared by the service/repository. A mock signature mismatch should not be hidden with broad relaxed mocks.

## Coroutine Testing

Use `coEvery` and `coVerify` only for functions declared `suspend`. Ordinary JPA repository and access-guard methods use `every` and `verify`, even when the test body can suspend. Use the module's configured coroutine test support for coroutine scheduling; do not add real sleeps merely to simulate asynchronous work.

## Exception Testing

Use `shouldThrow<ExpectedException>` for business rejection paths. Assert the relevant status/message when it is part of the contract, and keep production messages aligned with `CONTRIBUTING.md`. Do not change a failure assertion to success merely to accommodate a service bug.

## Reference Files and Commands

Discover actual service tests:

```bash
rg --files cowork-team/src/test cowork-channel/src/test cowork-project/src/test -g '*ServiceTest.kt'
```

Run the owning module, for example `./gradlew :cowork-team:test` or `(cd cowork-project && ./mvnw test)`. Root `./gradlew test` does not run Maven project tests.
