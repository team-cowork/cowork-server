package com.cowork.gateway.deployment

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.ReactiveHealthIndicator
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Configuration
@EnableScheduling
@EnableConfigurationProperties(DeploymentProperties::class)
internal class DeploymentConfiguration

@Component
class DeploymentStateCache(
    private val redis: ReactiveStringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    properties: DeploymentProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val confirmed = AtomicReference<Map<String, DeploymentState>?>(null)
    private val failing = AtomicBoolean(false)

    init {
        Gauge.builder("gateway.deployment.state.initialized", confirmed) {
            if (it.get() == null) 0.0 else 1.0
        }.description("Whether this Gateway has synchronized deployment state at least once")
            .register(meterRegistry)
        properties.destinations.keys.forEach { service ->
            Gauge.builder("gateway.deployment.state", confirmed) {
                val snapshot = it.get()
                when {
                    snapshot == null -> Double.NaN
                    snapshot[service]?.phase == "deploying" -> 1.0
                    snapshot[service]?.phase == "recovering" -> 2.0
                    else -> 0.0
                }
            }.tag("target_service", service)
                .description("Last confirmed deployment state: 0 normal, 1 deploying, 2 recovering")
                .register(meterRegistry)
        }
    }

    fun snapshot(): Map<String, DeploymentState>? = confirmed.get()

    @Scheduled(fixedDelay = 1000, initialDelay = 0)
    fun refresh() {
        try {
            val rows = redis.opsForHash<String, String>().entries(REDIS_KEY)
                .collectMap({ it.key }, { it.value })
                .block(Duration.ofSeconds(3)) ?: return
            val previous = confirmed.get()
            var failure: Exception? = null
            val states = rows.mapNotNull { (service, raw) ->
                try {
                    require(service.matches(SERVICE_NAME_PATTERN))
                    service to objectMapper.readValue(raw, DeploymentState::class.java)
                } catch (error: Exception) {
                    // Initial synchronization requires a fully validated snapshot.
                    if (previous == null) throw error
                    if (failure == null) failure = error
                    previous[service]?.let { service to it }
                }
            }.toMap()
            confirmed.set(states)
            failure?.let { throw it }
            if (failing.getAndSet(false)) logger.info("Recovered deployment state refresh")
        } catch (error: Exception) {
            // Keep the last confirmed state for entries that could not be refreshed.
            meterRegistry.counter("gateway.deployment.state.refresh.failures").increment()
            if (!failing.getAndSet(true)) {
                logger.warn("Failed to refresh deployment state: {}", error.javaClass.simpleName)
            }
        }
    }

    companion object {
        const val REDIS_KEY = "cowork:deployment:services"
        private val SERVICE_NAME_PATTERN = Regex("cowork-[a-z-]+")
    }
}

@Component("deploymentState")
class DeploymentStateHealthIndicator(private val cache: DeploymentStateCache) : ReactiveHealthIndicator {
    override fun health(): Mono<Health> = Mono.just(
        if (cache.snapshot() == null) Health.down().build() else Health.up().build(),
    )
}
