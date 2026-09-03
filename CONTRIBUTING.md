# Contributing

Kotlin/Java coding conventions for this project. `CLAUDE.md` and `.claude/rules/**` take precedence when they state a rule explicitly; this document is the default otherwise.

Spring MVC/JPA examples apply to `cowork-team`, `cowork-channel`, and `cowork-project`. `cowork-roadmap` uses Java with WebFlux/R2DBC; `cowork-preference` uses Kotlin with Vert.x and PostgreSQL clients. Do not apply Spring/JPA annotations to Vert.x code or copy blocking repository calls into reactive services. Go, Elixir, and TypeScript modules follow their own build files and module documentation.

Examples below omit imports unless the import itself is relevant. Existing code that differs from an explicit convention is not automatically an exception to it.

## Core Principles

1. **Clarity over Cleverness**: Write code that is easy to understand and maintain.
2. **Consistency**: Follow established patterns within the relevant module.
3. **Type Safety**: Use Kotlin's type system and null safety features.
4. **Separation of Concerns**: Maintain clear boundaries between layers and domain owners.
5. **Minimal Comments**: Add comments for non-obvious logic and required contracts, such as cross-service ID provenance.

## Naming Conventions

### Services

**Pattern**: `{Action}{Domain}Service`, with `{Action}{Domain}ServiceImpl` in the `impl/` package.

Examples include `CreateTeamService`, `QueryProjectService`, `DeleteChannelService`, and `ModifyRoadmapService`. The main use-case method is usually named `execute`. Shared helpers such as `TeamAccessGuard` and `TeamPermissionService` use names describing their responsibility.

### Controllers

**Pattern**: `{Domain}Controller`.

Examples include `TeamController`, `ProjectController`, and `RoadmapController`. A domain can have multiple controllers grouped by route or responsibility, such as `TeamChannelController` and `ProjectChannelController`.

### Request/Response DTOs

Keep the naming family used by the surrounding module:

| Modules | Request examples | Response examples |
| --- | --- | --- |
| `cowork-team`, `cowork-channel` | `CreateTeamRequest`, `CreateChannelRequest` | `TeamResponse`, `ChannelResponse` |
| `cowork-project`, `cowork-roadmap` | `CreateProjectReqDto`, `CreateRoadmapReqDto` | `ProjectResDto`, `RoadmapResDto` |

An action prefix is optional for shared or generic DTOs. Request and response DTOs live under `presentation/data/request` and `presentation/data/response` in these Spring business modules.

### Entities

Current domain entities use the domain name, such as `Team`, `Channel`, `Project`, and `Roadmap`. The package and mapping annotations identify the storage technology; these classes are not named `TeamJpaEntity` or `RoadmapJpaEntity`.

`Team`, `Channel`, and `Project` are JPA entities. `Roadmap` uses Spring Data relational mapping for R2DBC. Follow the module's existing mapping model and the table conventions in `.claude/rules/database.md`.

### Repositories

**Pattern**: `{Domain}Repository`, such as `TeamRepository`, `ChannelRepository`, `ProjectRepository`, and `RoadmapRepository`.

The first three use JPA; roadmap repositories use reactive Spring Data APIs. Do not introduce JPA-specific names or methods into R2DBC repositories.

## Query Parameter Binding

For Spring controllers, choose the binding method based on query parameter count and validation needs. Path variables and identity headers are separate inputs, not query parameters.

### Use `@RequestParam` for 1-2 Simple Parameters

The channel search endpoint has two query parameters:

```kotlin
@GetMapping("/channels")
fun searchChannels(
    @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
    @RequestParam teamId: Long,
    @RequestParam q: String,
): List<ChannelResponse> = searchChannelsService.execute(userId, teamId, q)
```

Use `@PathVariable` for a route segment such as `/{teamId}`; it does not demonstrate query parameter binding.

### Use `@ModelAttribute` + DTO for 3+ Parameters or Validation

Use `queryReq` for general queries and `searchReq` when search intent is clear. The following is an illustrative DTO for a query needing validation, not an existing endpoint contract:

```kotlin
data class SearchChannelsRequest(
    @field:Positive
    @param:Schema(description = "팀 ID", example = "1")
    val teamId: Long,
    @field:NotBlank
    @param:Schema(description = "검색어", example = "개발")
    val q: String,
    @field:Min(0)
    @param:Schema(description = "페이지 번호", defaultValue = "0")
    val page: Int = 0,
    @field:Min(1)
    @field:Max(100)
    @param:Schema(description = "페이지 크기", defaultValue = "20")
    val size: Int = 20,
)
```

Bind it as `@Valid @ModelAttribute searchReq: SearchChannelsRequest`. Keep the existing query string names when refactoring parameters into a DTO, and ensure Bean Validation is configured in the module.

## DTO Annotations

### Jackson Serialization

For Kotlin DTO properties, use `@field:` for Jackson annotations, not `@param:`. This is the project's annotation-target convention; it is not a claim that Jackson never supports constructor-parameter annotations. The same pattern appears in the project service's Kafka payload value:

```kotlin
data class GithubRepoSettingValue(
    @field:JsonProperty("label_auto_apply")
    val labelAutoApply: Boolean,
)
```

Apply `@field:JsonAlias` in the same way when an input alias is needed. Kotlin use-site targets do not apply to Java records or Vert.x `JsonObject` payloads.

### Swagger Documentation

- **Kotlin request DTO properties**: Use `@param:Schema`.
- **Kotlin response DTO properties**: Use `@field:Schema`.
- **Bean Validation on Kotlin DTO properties**: Use `@field:NotBlank`, `@field:Positive`, and other field constraints, with `@Valid` at the controller boundary.
- **Java DTOs**: Use ordinary annotations on record components or fields; Java has no `@param:`/`@field:` syntax.

```kotlin
data class CreateTeamRequest(
    @param:Schema(description = "팀 이름", example = "코워크팀", required = true)
    val name: String,
    @param:Schema(description = "팀 설명")
    val description: String?,
    @param:Schema(description = "팀 아이콘 URL")
    val iconUrl: String?,
)

data class IconConfirmResponse(
    @field:Schema(description = "업로드 완료 후 사용할 CDN URL")
    val iconUrl: String,
)
```

Some existing DTOs have untargeted or missing `@Schema` annotations. That does not change the explicit target convention above.

## Architecture Patterns

### Layer Structure

Spring business modules separate responsibilities as follows:

```text
Controller → Service → Repository
```

**Controller Responsibilities:**

- Handle HTTP input, validation (`@Valid` where constraints are defined), and route mapping.
- Read authenticated identity from Gateway-forwarded headers and pass it to the service.
- Return DTOs or lists directly. `cowork-team` configures the SDK response wrapper; `cowork-channel` and `cowork-project` disable it and rely on the Gateway's `ApiResponseWrapperFilter` for eligible JSON responses.
- Keep `204 No Content` responses bodyless, as the team/channel delete endpoints do. If an endpoint returns a message with no data, it can explicitly return `CommonApiResponse<Nothing>` with a body-bearing status.
- Do not assume every response is wrapped: the Gateway bypasses configured paths, non-JSON/streaming responses, and responses exceeding its size limit, and preserves already wrapped responses.

**Service Responsibilities:**

- Business logic, resource authorization, and transaction management.
- Entity-to-DTO conversion through the module's factories: Kotlin business modules commonly use `Response.of(entity)`; roadmap uses `ResDto.from(entity)`.
- Coordinate repositories and the owning domain's mutation/outbox write in one transaction.
- Use local projections for another service's durable state. Follow `.claude/rules/kafka-projections.md` for commands, ownership, ordering, and readiness.

**Repository Responsibilities:**

- Data access and parameterized queries, without business decisions.
- Use the module's configured JPA or R2DBC APIs. QueryDSL is not currently a configured dependency in these modules.

### Example

This query flow uses the existing team access guard; the guard checks membership and resolves a missing team to an `ExpectedException`.

```kotlin
@RestController
@RequestMapping("/teams")
class TeamQueryController(private val queryTeamService: QueryTeamService) {
    @GetMapping("/{teamId}")
    fun getTeam(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
    ): TeamResponse = queryTeamService.execute(userId, teamId)
}

interface QueryTeamService {
    fun execute(userId: Long, teamId: Long): TeamResponse
}

@Service
class QueryTeamServiceImpl(private val teamAccessGuard: TeamAccessGuard) : QueryTeamService {
    @Transactional(readOnly = true)
    override fun execute(userId: Long, teamId: Long): TeamResponse {
        teamAccessGuard.requireMemberExists(teamId, userId)
        return TeamResponse.of(teamAccessGuard.findTeamOrThrow(teamId))
    }
}
```

The controller above shows only the query route; the actual route lives in `TeamController`. For write flows, consult `CreateTeamServiceImpl` and its event publishers rather than omitting the transactional outbox from a copied example.

## Dependency Injection

Use constructor injection, not field injection. In Kotlin, dependencies are normally constructor `private val` properties. Java roadmap services use final fields with Lombok `@RequiredArgsConstructor` or an explicit constructor.

```kotlin
@Service
class TeamAccessGuard(
    private val teamRepository: TeamRepository,
    private val teamMemberRepository: TeamMemberRepository,
)
```

Configuration values follow the same approach: use constructor parameters or a configuration properties object instead of `@Autowired lateinit var` or field-injected `@Value`.

## Kotlin Style

### Prefer `val` over `var`

Use `val` for references that are not reassigned. JPA entities can use `var` for mutable persisted state, as `Team.name` and `Project.status` do; preferring `val` does not make those domain updates impossible.

```kotlin
val team = teamAccessGuard.findTeamOrThrow(teamId)
team.update(name = request.name, description = request.description, iconUrl = null)
```

### Null Safety

Use nullable types when absence is a valid result. When a required resource is missing, throw the appropriate business exception rather than dereferencing `Optional.get()` or forcing `!!`.

```kotlin
fun findTeamOrThrow(teamId: Long): Team = teamRepository.findById(teamId).orElseThrow {
    ExpectedException("팀을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
}

val description = team.description ?: "설명 없음"
```

Use `requireNotNull` only for an internal invariant that must hold, such as audited timestamps when building a response from a persisted entity.

### Type Inference

Use explicit return types for public APIs and inference for local variables. Omit the `Unit` return type.

```kotlin
interface QueryTeamService {
    fun execute(userId: Long, teamId: Long): TeamResponse
}

interface DeleteTeamService {
    fun execute(userId: Long, teamId: Long)
}

val teams = teamRepository.findAll()
val count = teams.size
```

## Code Formatting

`.editorconfig` is the source of truth for editor settings. It specifies four spaces and, for Kotlin, a 120-character limit and the `intellij_idea` KtLint style. Kotlin import layout follows that style; do not add arbitrary blank groups. Wildcard-import and property-naming checks are disabled in the current KtLint configuration. Import types instead of writing fully qualified type names inline, as required by `.claude/rules/fail-safe.md`.

Run commands from the repository root:

| Scope | Check | Format |
| --- | --- | --- |
| Gradle Kotlin modules (`gateway`, `config`, `channel`, `team`) | `./gradlew ktlintCheck` | `./gradlew ktlintFormat` |
| One Gradle Kotlin module | `./gradlew :cowork-team:ktlintCheck` | `./gradlew :cowork-team:ktlintFormat` |
| Java roadmap | `./gradlew :cowork-roadmap:spotlessCheck` | `./gradlew :cowork-roadmap:spotlessApply` |

Roadmap's Spotless configuration uses `cowork-roadmap/config/eclipse-java-formatter.xml` and its own import order. `compileJava` and `compileTestJava` depend on `spotlessApply`, so compiling or testing roadmap can reformat Java files.

`cowork-project` (Maven) and `cowork-preference` (Amper) do not apply the Gradle Kotlin plugin and have no `ktlintFormat` task. Root KtLint commands do not format them; follow `.editorconfig` and review their diffs explicitly. There is no repository-wide formatter for all languages.

Project hooks attempt root KtLint after recognized Kotlin edits and root Spotless after recognized Java/Kotlin/Groovy edits. The suffix that triggers a hook does not expand the formatter's configured targets. Hook failures are reported but do not replace an explicit check.

## Error Handling

### Use ExpectedException Directly

In Spring services using the SDK, instantiate `ExpectedException` directly for business failures such as missing resources, conflicts, or insufficient permissions. Do not create subclasses or wrappers.

Messages must be Korean (합쇼체) ending with a period. Do not include dynamic data such as IDs or names: these messages are displayed directly to end users.

```kotlin
val team = teamRepository.findById(teamId).orElseThrow {
    ExpectedException("팀을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
}

if (!teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)) {
    throw ExpectedException("팀 멤버가 아닙니다.", HttpStatus.FORBIDDEN)
}
```

Infrastructure failures retain their appropriate exception types, such as `IOException` or timeout exceptions. Do not disguise programming errors or storage failures as ordinary business rejections. Non-Spring services use their own error-handling mechanisms rather than importing the JVM SDK.

### Exception Handler

Exception handling is configured per service, not in a shared monorepo module: team has `AdditionalExceptionHandler`; channel, project, and roadmap have their own `GlobalExceptionHandler`. Follow the local handler and response contract when adding an error path. Downstream service HTTP responses and the Gateway's wrapped public responses can differ.

## Logging

### Use SLF4J with the Module's Logging Backend

JVM code uses the SLF4J API. Spring modules use Logback; `cowork-preference` binds SLF4J to Log4j2 through its Amper dependencies. Never use `println()` for application logging.

```kotlin
private val log = LoggerFactory.getLogger(TeamLifecycleConsumer::class.java)

// Inside the consumer's error path:
// log.error("Failed to process team lifecycle event", exception)
```

Use the existing logger property (`log` or `logger`) rather than assuming an undeclared `logger()` helper is available.

### Log Message Style

- **Language**: English, with verb-led sentences.
- **Format**: SLF4J `{}` placeholders, not string interpolation.
- Include useful context without credentials, tokens, or other secrets.

```kotlin
log.info("Deleted {} expired invitations", deletedCount)
log.error("Failed to publish outbox event {}", eventId, exception)
log.warn("Rejected operation for team {}", teamId)
```

### Log Levels

- `ERROR`: Unrecoverable errors.
- `WARN`: Recoverable errors or unexpected states.
- `INFO`: Important business events.
- `DEBUG`: Detailed diagnostic information.
- `TRACE`: Very detailed diagnostic information.

## Testing

### Framework

Kotest with MockK is the convention for new Kotlin Spring business tests. Existing Spring Kotlin tests also use JUnit 5 with MockK; `cowork-config` uses JUnit 5 without Kotest. Roadmap uses JUnit 5, Mockito, and Reactor `StepVerifier`. Preference uses JUnit 5/Vert.x test support and MockK. Use the dependencies and runner actually configured in the module; do not impose Kotest on Java, Vert.x, Go, Elixir, or TypeScript tests.

This example tests the missing-team behavior in the query service shown above, using its real dependency and method names:

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
            context("멤버십 확인 후 팀을 찾을 수 없는 경우") {
                it("ExpectedException을 던진다") {
                    // Given
                    every { accessGuard.requireMemberExists(10L, 1L) } just Runs
                    every { accessGuard.findTeamOrThrow(10L) } throws
                        ExpectedException("팀을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)

                    // When & Then
                    shouldThrow<ExpectedException> {
                        service.execute(userId = 1L, teamId = 10L)
                    }
                }
            }
        }
    }
})
```

### Test Structure

- Use Given-When-Then inside Kotest `it` blocks.
- Test one behavior or scenario per test; use related assertions and interaction verification as needed.
- Name Kotest tests in Korean: `describe("ClassName 클래스의")`, `describe("methodName 메서드는")`, `context("상황 설명")`, and `it("기대 동작")`.
- Mock external dependencies with MockK in Kotlin; use the module's equivalent in other languages.
- Use `beforeEach` for shared setup and `afterEach` for cleanup when needed.
- Verify serialized Kafka contracts, idempotency, ordering, and recovery behavior when changing messaging code; see `.claude/rules/kafka-projections.md`.

Run the owning module's tests from the repository root:

| Scope | Command |
| --- | --- |
| Gradle Kotlin module | `./gradlew :cowork-team:test` (replace `team` with `gateway`, `config`, or `channel`) |
| Java roadmap | `./gradlew :cowork-roadmap:test` |
| Maven project | `(cd cowork-project && ./mvnw test)` |
| Amper preference | `./gradlew :cowork-preference:amperTest` |

The Amper wrapper uses `KOTLIN_CLI`, defaulting to `~/.local/bin/kotlin`; install that toolchain before running it. There is no `:cowork-project:test` or `:cowork-preference:test` Gradle task. An unqualified root `./gradlew test` does not cover those wrappers or the non-JVM services. Use each non-JVM module's documented native test command.

## Security

### No Hardcoded Secrets

Inject secrets through environment variables, Vault, or configured secret providers. For Spring values, use constructor injection or configuration properties. JWT validation belongs to the Gateway, with the documented chat WebSocket exception in `.claude/rules/security.md`.

```yaml
# Config Server YAML; resolve this through a supported client or secret source.
spring:
  datasource:
    password: ${DB_PASSWORD}
```

Non-Spring clients do not all resolve `${VAR}` placeholders; follow `.claude/rules/config.md` for their actual configuration behavior. Do not replace unresolved placeholders with committed credentials.

### SQL Injection Prevention

Use derived repository queries or bound parameters. Current JPA repositories can express queries without adding QueryDSL:

```kotlin
interface TeamRepository : JpaRepository<Team, Long> {
    @Query("select t from Team t where t.name = :name")
    fun findByName(@Param("name") name: String): List<Team>
}
```

The query method is an illustrative addition, not an existing endpoint. Never interpolate request values into SQL/JPQL strings. Apply the same binding rule to R2DBC and Vert.x SQL clients.

## Common Mistakes to Avoid

### DTO Annotations

- Use `@field:JsonProperty`, not `@param:JsonProperty`, on Kotlin DTO properties.
- Use `@field:Schema` on Kotlin response properties and `@param:Schema` on request properties.

### Kotlin Style

- Prefer `val` for references that are not reassigned.
- Use constructor injection.
- Comment non-obvious logic and required contracts, not self-evident operations.

### Transaction Management

Put Spring business transaction boundaries on service methods. Use method-level `@Transactional` for writes and `@Transactional(readOnly = true)` for database reads; do not move business boundaries to controllers or repositories, and do not use class-level transaction annotations.

```kotlin
@Service
class QueryTeamServiceImpl(private val teamAccessGuard: TeamAccessGuard) : QueryTeamService {
    @Transactional(readOnly = true)
    override fun execute(userId: Long, teamId: Long): TeamResponse {
        teamAccessGuard.requireMemberExists(teamId, userId)
        return TeamResponse.of(teamAccessGuard.findTeamOrThrow(teamId))
    }
}
```

Keep state mutations and their outbox records atomic. Infrastructure components such as projection processors/checkpoint stores and outbox writers also declare method-level transactions for their own persistence units; they do not replace the service's business transaction. Do not add a database transaction to a method merely because it is named `execute` when it only calls an external provider or reads a cache.

For roadmap, preserve the reactive chain so the R2DBC transaction covers the subscribed work; do not call `block()` or start detached subscriptions inside the transaction. Preference uses explicit Vert.x SQL transactions instead of Spring annotations.
