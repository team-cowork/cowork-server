---
paths:
  - "cowork-*/src/**/*.kt"
  - "cowork-*/src/**/*.java"
---

# JVM Conventions

- Kotlin DTO properties use `@field:` for Jackson annotations and Bean Validation constraints. Use `@param:Schema` on request properties and `@field:Schema` on response properties.
- Spring SDK business failures use `ExpectedException` directly, without subclasses or wrappers. User-facing messages use Korean 합쇼체, end with a period, and exclude dynamic IDs or names.
- JVM log messages use English, verb-led sentences.
- Spring business transactions belong on service methods, never on classes: `@Transactional` for writes and `@Transactional(readOnly = true)` for database reads. Provider-only or cache-only methods do not need a database transaction.
