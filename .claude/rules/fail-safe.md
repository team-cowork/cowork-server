---
paths:
  - "**/*.kt"
  - "**/*.java"
  - "**/*.gradle"
  - "**/*.kts"
---

# No Fully Qualified Names (FQN)

Never use fully qualified type names inline. Always add an `import` at the top of the file and use the short name instead.

```kotlin
// wrong
private fun org.springframework.cloud.client.ServiceInstance.isUp(): Boolean { ... }

// correct
import org.springframework.cloud.client.ServiceInstance

private fun ServiceInstance.isUp(): Boolean { ... }
```
