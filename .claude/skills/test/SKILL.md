---
name: test
description: Run tests with coverage analysis and report results. Determines appropriate test scope (single test / module / all) based on context and analyzes failures in detail.
allowed-tools: Bash, Glob, Grep
---

# Test Guide

## Determine Test Scope

Read the target module's build manifest and `CONTRIBUTING.md`. Choose the smallest scope that verifies the requested behavior. Commands below run from the repository root unless a working directory is specified.

| Module/runtime                   | Command                                                        |
|----------------------------------|----------------------------------------------------------------|
| Gradle-owned JVM module          | `./gradlew :<module>:test`                                     |
| One Gradle test class            | `./gradlew :<module>:test --tests "fully.qualified.ClassName"` |
| Maven project                    | `cd cowork-project && ./mvnw test`                             |
| Amper preference                 | `./gradlew :cowork-preference:amperTest`                       |
| Authorization/notification/voice | `go -C cowork-<service> test ./...`                            |
| Elixir user                      | `cd cowork-user && mix test`                                   |
| NestJS chat                      | `npm --prefix cowork-chat test -- --runInBand`                 |
| Static promotion site            | `npm --prefix cowork-promotion test`                           |

Root `./gradlew test` runs Gradle test tasks only. It does not test Maven project, Amper preference, Go, Elixir, or npm services. The wrappers do not define `:cowork-project:test` or `:cowork-preference:test`. Monitoring is config-only; use its documented config checks.

For a whole-repository request, run each applicable runtime separately and report any module not executed. Do not pass Gradle's `--tests` option to Maven/Amper; use options supported by the selected runner.

## Run and Analyze

Confirm required toolchains and test infrastructure from the module README. Report failures as observed, including test/class, exception, relevant stack frames, and likely cause. Separate toolchain or environment failures from failing assertions. Run a wider scope only when the change or failure gives a reason to do so.

Report executed commands, passed/failed/skipped counts where available, and any limitation. Do not describe a successful build as proof that all tests ran.

## Coverage

When coverage is requested, use the configured runner (for example chat `test:cov`, Go `-cover`, or Mix `--cover`). Check the module build before naming a JVM coverage task; this repository does not define a universal JaCoCo/Kover report task. If coverage is unavailable, say so instead of reporting inferred percentages or adding tooling without a task-related reason.
