# Contributing

Follow [AGENTS.md](AGENTS.md) and the matching [.claude/rules](.claude/rules); their explicit rules take precedence over this guide. Follow the owning module's patterns, keep code clear and type-safe, and comment only non-obvious logic or required contracts. Existing code does not override an explicit rule.

Spring MVC/JPA conventions apply to `cowork-team`, `cowork-channel`, `cowork-project`, and the Java-based `cowork-roadmap`. `cowork-preference` uses Kotlin, Vert.x, and PostgreSQL clients: do not add Spring/JPA annotations or blocking repository calls to its event loops. Go, C#, and TypeScript modules follow their own build files and documentation.

## Naming

| Type | Convention |
|------|------------|
| Service | `{Action}{Domain}Service`, with `{Action}{Domain}ServiceImpl` in `impl/`; the use-case method is usually `execute` |
| Shared helper | Name its responsibility, such as `TeamAccessGuard` or `TeamPermissionService` |
| Controller | `{Domain}Controller`; split by route or responsibility when needed |
| Entity | Domain name, such as `Team` or `Roadmap`; follow the module's mapping model and [database rules](.claude/rules/database.md) |
| Repository | `{Domain}Repository`; Spring business modules use Spring Data JPA, with native SQL for database-specific queries and locking |

Keep the module's DTO naming family. Action prefixes are optional for shared DTOs. Spring business DTOs live under `presentation/data/request` and `presentation/data/response`.

| Modules | Request examples | Response examples |
|---------|------------------|-------------------|
| `cowork-team`, `cowork-channel` | `CreateTeamRequest`, `CreateChannelRequest` | `TeamResponse`, `ChannelResponse` |
| `cowork-project`, `cowork-roadmap` | `CreateProjectReqDto`, `CreateRoadmapReqDto` | `ProjectResDto`, `RoadmapResDto` |

## HTTP input and DTOs

For Spring query parameters, use `@RequestParam` for one or two simple values. Use `@ModelAttribute` with a DTO for three or more values, or when validation is needed. Path variables and identity headers do not count as query parameters; route segments use `@PathVariable`.

Name query DTO parameters `queryReq`, or `searchReq` for searches. Preserve query string names when refactoring. For validated DTOs, use `@Valid` with `@ModelAttribute` and ensure Bean Validation is configured.

| Kotlin DTO annotation | Target |
|-----------------------|--------|
| Jackson, including `JsonProperty` and `JsonAlias` | `@field:` |
| Request Swagger schema | `@param:Schema` |
| Response Swagger schema | `@field:Schema` |
| Bean Validation constraints | `@field:`, with `@Valid` at the controller boundary |

These are project conventions; they do not describe every target Jackson supports. Java records and fields use ordinary annotations. Kotlin use-site targets do not apply to Vert.x `JsonObject` payloads. See [JVM conventions](.claude/rules/jvm-conventions.md).

## Architecture and transactions

| Layer | Responsibility |
|-------|----------------|
| Controller | Map routes, validate input, pass Gateway-forwarded identity to services, and return DTOs or lists |
| Service | Apply business rules and resource authorization, manage transactions, and convert entities through module factories such as `Response.of(entity)` or roadmap's `ResDto.from(entity)` |
| Repository | Access data through configured JPA/JDBC APIs and parameterized queries; keep business decisions in services |

QueryDSL is not configured in these modules. For another service's durable state, use local projections and follow the [service boundaries](.claude/rules/service-boundaries.md) and [Kafka projection rules](.claude/rules/kafka-projections.md).

### Responses

`cowork-team`, `cowork-channel`, and `cowork-project` disable the SDK response wrapper. Gateway's `ApiResponseWrapperFilter` wraps eligible JSON responses. It bypasses configured paths, streaming responses, unsupported content types, and responses over its size limit.

Keep `204 No Content` responses bodyless. A message-only response may use `CommonApiResponse<Nothing>` with a status that permits a body. For eligible responses already in `CommonApiResponse` format, Gateway preserves `message` and non-null `data`, and sets `status` and `code` from the HTTP status.

### Transactions

- Put Spring business transactions on service methods: `@Transactional` for writes and `@Transactional(readOnly = true)` for database reads. Do not put them on classes, controllers, or repository methods as substitutes for service boundaries.
- Write state changes and their outbox records in one transaction. Projection processors, checkpoint stores, and outbox writers may use method-level transactions for their own persistence units.
- Provider-only and cache-only methods do not need database transactions.
- Roadmap uses JPA for domain and projection storage, with native queries for MySQL-specific projection SQL. Projection data and checkpoints share the processor's transaction. Flush temporary node positions before saving the final order, and load entities after acquiring the roadmap lock.
- Preference uses explicit Vert.x SQL transactions.

## Injection and Kotlin style

- Use constructor injection for dependencies and configuration values, or a configuration properties object. Kotlin dependencies use constructor `private val` properties. Java roadmap services use final fields with `@RequiredArgsConstructor` or an explicit constructor. Avoid field-injected `@Autowired` and `@Value`.
- Prefer `val` for references that are not reassigned. JPA entities may use `var` for mutable persisted state.
- Use nullable types for valid absence. For a missing required resource, throw the appropriate business exception; avoid `Optional.get()` and `!!`. Use `requireNotNull` only for internal invariants, such as persisted audit timestamps.
- Declare public API return types, infer local variable types, and omit `Unit` return types.
- Import types instead of writing fully qualified names inline.

## Formatting

[.editorconfig](.editorconfig) defines editor settings: four spaces by default, tabs for XO-linted TypeScript/JavaScript in `cowork-chat` and `cowork-github`, and Kotlin's 120-character limit with `intellij_idea` KtLint style. Follow its import layout without arbitrary blank groups. Wildcard-import and property-naming checks are disabled.

Run these commands from the repository root:

| Scope | Check | Format |
|-------|-------|--------|
| Gradle Kotlin (`gateway`, `channel`, `team`; replace `team`) | `(cd cowork-team && ./gradlew ktlintCheck)` | `(cd cowork-team && ./gradlew ktlintFormat)` |
| Kotlin Toolchain preference | `cowork-preference/ktlint` | `cowork-preference/ktlint --format` |
| Bun chat | `(cd cowork-chat && bun run lint --max-warnings=0)` | `(cd cowork-chat && bun run lint:fix)` |
| Node promotion | `npm --prefix cowork-promotion run lint -- --max-warnings=0` | `npm --prefix cowork-promotion run lint:fix` |
| Bun github | `(cd cowork-github && bun run lint --max-warnings=0)` | `(cd cowork-github && bun run lint:fix)` |
| Java roadmap | `(cd cowork-roadmap && ./gradlew spotlessCheck)` | `(cd cowork-roadmap && ./gradlew spotlessApply)` |

`cowork-promotion`, `cowork-chat`, and `cowork-github` use XO's default rules and file discovery. Fix diagnostics in code; do not add inline `eslint-disable` comments or rule overrides. Promotion has no `xo.config.mjs`. Chat and GitHub use it only to give `import-x/extensions` leading dots, which `eslint-config-xo` omits; without them, `import-x/no-cycle` checks nothing. Each module's ESLint adapter passes XO configuration to editors. Review fixes and run the module build; CI rejects warnings and errors.

Roadmap uses `cowork-roadmap/config/eclipse-java-formatter.xml` and its configured import order. `check` and `build` run `spotlessCheck`; compilation does not format files. Run `spotlessApply` explicitly.

Kotlin Toolchain has no ktlint integration, so `cowork-preference/ktlint` runs the same ktlint release as the Gradle Kotlin modules, and the Dockerfile `verification` stage rejects violations. Maven-based `cowork-project` has no formatter task. Follow `.editorconfig` and review its diffs. There is no repository-wide formatter.

Hooks run the owning module's formatter when available. File suffix triggers do not expand formatter targets. Hooks report failures and skip modules without a formatter; they do not replace explicit checks.

## Errors and logging

Spring SDK business failures use `ExpectedException` directly, without subclasses or wrappers. Messages use Korean 합쇼체, end with a period, and contain no dynamic IDs or names. Preserve infrastructure exception types such as `IOException` and timeouts; do not turn programming or storage failures into business rejections. Non-Spring services use their own error handling.

Follow each service's handler and response contract: team uses `AdditionalExceptionHandler`; channel, project, and roadmap have their own `GlobalExceptionHandler`. Downstream HTTP responses can differ from Gateway-wrapped public responses.

JVM code uses SLF4J with Logback in Spring modules and Log4j2 in Preference. Use the existing `log` or `logger` property, not `println()` or an undeclared `logger()` helper. Messages use English, start with a verb, and use SLF4J `{}` placeholders instead of interpolation. Include useful context without secrets. See [logging rules](.claude/rules/logging.md).

| Level | Use |
|-------|-----|
| `ERROR` | Unrecoverable errors |
| `WARN` | Recoverable errors or unexpected states |
| `INFO` | Important business events |
| `DEBUG` | Detailed diagnostics |
| `TRACE` | Very detailed diagnostics |

## Testing and build tools

Write and run unit tests for core business logic, including business authorization and access rules. Avoid regression and integration tests; when one is necessary during development, write and run it, then delete it before committing so that it is not part of the final deliverable. Outside that exception, do not add or run tests for controllers, framework wiring, configuration shape, migrations, repositories, or transport mechanics. Kafka serialization, idempotency, ordering, checkpoints, snapshot markers, retries, quarantine, and recovery are not exceptions.

This scope does not restrict CI or coverage discovery. Keep each module's normal test command; do not add test allowlists.

Use the module's configured runner and dependencies:

| Modules | Frameworks |
|---------|------------|
| Kotlin Spring | Kotest and MockK |
| Roadmap | JUnit 5, Mockito, and AssertJ |
| Preference | JUnit 5/Vert.x test support and MockK |
| Applet | MUnit |
| Other runtimes | Their configured native tools |

- Use Describe-Context-It with Given-When-Then inside each scenario. Test one behavior per test; related assertions and interaction checks may share it.
- Kotlin Spring uses `DescribeSpec` for multiple scenarios. Name Kotest tests in Korean, identifying the class and method: nested `describe("ClassName 클래스의")` / `describe("methodName 메서드는")`, or combined `describe("ClassName 클래스의 methodName 메서드는")`, followed by `context("상황 설명")` and `it("기대 동작")`.
- A focused JUnit class may use `{method}_{context}_{expectation}` names; use `@Nested` for multiple contexts of one method. Jest uses `describe` / `it`, ExUnit uses `describe` / `test`, and Go uses behavior-named `Test...` functions with `t.Run` for related contexts.
- Mock external dependencies with MockK in Kotlin or the module's equivalent. Use `beforeEach` for shared setup and `afterEach` for cleanup when needed.

Run the owning module's tests from the repository root:

| Scope | Command |
|-------|---------|
| Gradle Kotlin | `(cd cowork-team && ./gradlew test)` (replace `team` with `gateway` or `channel`) |
| Java roadmap | `(cd cowork-roadmap && ./gradlew test)` |
| Maven project | `(cd cowork-project && ./mvnw test)` |
| Kotlin Toolchain preference | `(cd cowork-preference && ./kotlin test)` |
| sbt applet | `(cd cowork-applet && ./sbtw test)` |

Each Gradle module owns its Wrapper, settings, version catalog, and build configuration; versions may differ by module. Run the module's Wrapper from its directory. There is no root Gradle build. Use the checked-in Maven wrapper for Project and native commands for other runtimes.

Preference uses `cowork-preference/kotlin` or `kotlin.bat` for local builds, Docker, and CI. The wrapper provisions the pinned toolchain and JDK without a global CLI. Update both wrappers together with `./kotlin update --target-version=<version>` from the module directory.

Applet uses Scala 3 and Play on Java 25, the newest Java that Play 3.0 supports. `cowork-applet/sbtw` downloads the pinned sbt launcher for local builds, Docker, and CI. Its SurrealDB definitions live in `conf/surreal/schema.surql`, use `IF NOT EXISTS`, and run at application start; there is no migration history, so backfill existing records separately when a stored shape changes.

## Security

- Inject secrets through environment variables, Vault, or configured secret providers. Follow the [configuration rules](.claude/rules/config.md); non-Spring runtimes do not all resolve `${VAR}` placeholders. Never commit credentials to work around unresolved placeholders.
- Gateway validates access JWTs, with the Chat WebSocket exception in the [security rules](.claude/rules/security.md).
- Use derived repository queries or bound parameters in JPA, JDBC, and Vert.x SQL. Never interpolate request values into SQL/JPQL.
- Run `bun audit` in both `cowork-chat` and `cowork-github`; use `bun audit --production` to check only runtime dependencies.
