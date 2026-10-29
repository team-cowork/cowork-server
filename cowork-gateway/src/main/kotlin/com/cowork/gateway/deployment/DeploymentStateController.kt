package com.cowork.gateway.deployment

import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

@RestController
class DeploymentStateController(private val cache: DeploymentStateCache, private val inflight: DeploymentInflight) {
    @GetMapping("/internal/deployment-state")
    fun state(exchange: ServerWebExchange): ResponseEntity<*> {
        if (exchange.request.remoteAddress?.address?.isLoopbackAddress != true) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build<Any>()
        }
        val snapshot = cache.snapshot()
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(
                mapOf(
                    "initialized" to (snapshot != null),
                    "services" to (snapshot ?: emptyMap()),
                    "inflight" to inflight.snapshot(),
                ),
            )
    }
}
