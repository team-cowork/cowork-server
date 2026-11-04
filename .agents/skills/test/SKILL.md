---
name: test
description: Run tests with coverage analysis and report results. Determines appropriate test scope (single test / module / all) based on context and analyzes failures in detail.
allowed-tools: Bash, Glob, Grep
---

## Determine Test Scope

Based on the user's request or changed files, choose the appropriate scope:

| Scope               | Command                                                               |
|---------------------|-----------------------------------------------------------------------|
| Specific test class | `(cd <module> && ./gradlew test --tests "fully.qualified.ClassName")` |
| Specific module     | `(cd <module> && ./gradlew test)`                                     |
| Multiple modules    | Run each selected module's native test command separately             |

Discover the owning modules from their local `gradlew` and `settings.gradle.kts`, `pom.xml`, `module.yaml`, or `package.json`. There is no root Gradle build. Use the native runner for Maven, Kotlin Toolchain, and non-JVM modules.

## Run Tests

Execute the chosen command. Add `--info` if failures need detailed output:

```bash
(cd <module> && ./gradlew test --info)
```

## Analyze Results

After the run, report:

- Total tests / passed / failed / skipped
- Execution time
- For each failure:
  - Test name and class
  - Failure message and root cause
  - Relevant stack trace lines

If there are failures, read the relevant source files and suggest the most likely fix.
