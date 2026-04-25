# Fail-Safe Principle

When handling unknown implementations or states, **never assume success or a healthy state — default to failure or unhealthy**.

## Example

```kotlin
// BAD: assumes unknown implementation is UP
private fun ServiceInstance.isUp(): Boolean {
    if (this is EurekaServiceInstance) {
        return instanceInfo.status == InstanceInfo.InstanceStatus.UP
    }
    return true
}

// GOOD: treats unknown implementation as DOWN
private fun ServiceInstance.isUp(): Boolean {
    if (this is EurekaServiceInstance) {
        return instanceInfo.status == InstanceInfo.InstanceStatus.UP
    }
    return false
}
```

## Rationale

- A false positive (incorrect DOWN alert) can be investigated and dismissed.
- A false negative (incorrect UP report) silently hides a real failure.
- Only trust explicitly known implementations; treat everything else conservatively.
