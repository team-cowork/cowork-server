---
paths:
  - "cowork-*/src/main/**/*.{kt,java}"
  - "cowork-*/src/**/*.ts"
  - "cowork-*/internal/apidoc/**"
  - "cowork-user/src/Http/**"
  - "cowork-applet/app/**/*Controller.scala"
  - "cowork-applet/conf/routes"
  - "cowork-applet/conf/swagger.yml"
  - "**/openapi.{json,yaml,yml}"
---

# OpenAPI documentation

- Keep descriptions concise. Explain behavior the caller needs to know.
- Use consistent Korean terminology and sentence endings. Use short noun phrases for summaries and labels, and 합쇼체 for full sentences.
- Use standard OpenAPI fields for types, required properties, limits, security requirements, status codes, and examples. Omit prose that repeats those fields.
- Avoid em dashes, middle dots, and parentheses in prose.
