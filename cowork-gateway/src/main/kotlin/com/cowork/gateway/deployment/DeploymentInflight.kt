package com.cowork.gateway.deployment

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@Component
class DeploymentInflight {
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    fun enter(service: String) {
        counts.computeIfAbsent(service) { AtomicInteger() }.incrementAndGet()
    }

    fun leave(service: String) {
        counts[service]?.decrementAndGet()
    }

    fun snapshot(): Map<String, Int> = counts.mapValues { it.value.get() }.filterValues { it > 0 }
}
