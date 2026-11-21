---
paths:
  - "**/*.{kt,java,scala,gradle,kts,sbt}"
---

# JVM Conventions

- Import types instead of using fully qualified names inline.
- In all Spring modules, name constructor-injected dependency parameters and their fields or properties after the declared type in lower camel case. For example, inject `S3Client` as `s3Client`.
- Kotlin DTO properties use `@field:` for Jackson annotations and Bean Validation constraints. When documenting a request body property, put its schema metadata in one `@get:Schema` so springdoc reads it from the getter. Use `@param:Schema` on query DTO properties and `@field:Schema` on response properties.
- Spring SDK business failures use `ExpectedException` directly, without subclasses or wrappers. User-facing messages use Korean 합쇼체, end with a period, and exclude dynamic IDs or names.
- JVM log messages use English, verb-led sentences.
- Spring business transactions belong on service methods, never on classes: `@Transactional` for writes and `@Transactional(readOnly = true)` for database reads. Provider-only or cache-only methods do not need a database transaction.
