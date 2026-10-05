package com.cowork.team.global.outbox

import com.cowork.team.global.outbox.delivery.TeamKafkaSender
import com.cowork.team.global.outbox.delivery.TeamOutboxStore
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

data class OutboxRelaySettings(
    val batchSize: Int,
    val claimLeaseMs: Long,
    val sendTimeoutMs: Long,
    val maxAttempts: Int,
    val backoffInitialMs: Long,
    val backoffMaxMs: Long,
) {
    init {
        require(batchSize > 0) { "Kafka outbox batch size must be positive." }
        require(sendTimeoutMs > 0) { "Kafka outbox send timeout must be positive." }
        require(claimLeaseMs > sendTimeoutMs) { "Kafka outbox claim lease must exceed the send timeout." }
        require(maxAttempts > 0) { "Kafka outbox max attempts must be positive." }
        require(backoffInitialMs > 0) { "Kafka outbox initial backoff must be positive." }
        require(backoffMaxMs >= backoffInitialMs) {
            "Kafka outbox maximum backoff must not be shorter than the initial backoff."
        }
    }
}

@Component
class KafkaOutboxRelay(
    dataSource: DataSource,
    @Qualifier("teamGithubDlqKafkaTemplate") kafkaTemplate: KafkaTemplate<String, Any>,
    objectMapper: ObjectMapper,
    meterRegistry: MeterRegistry,
    @Value("\${kafka.outbox.batch-size:100}") batchSize: Int,
    @Value("\${kafka.outbox.claim-lease-ms:30000}") claimLeaseMs: Long,
    @Value("\${kafka.outbox.send-timeout-ms:10000}") sendTimeoutMs: Long,
    @Value("\${kafka.outbox.max-attempts:8}") maxAttempts: Int,
    @Value("\${kafka.outbox.backoff-initial-ms:5000}") backoffInitialMs: Long,
    @Value("\${kafka.outbox.backoff-max-ms:600000}") backoffMaxMs: Long,
) {
    private val serviceName = "cowork-team"
    private val logger = LoggerFactory.getLogger(javaClass)
    private val claimOwner = "$serviceName:${UUID.randomUUID()}"
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val settings = OutboxRelaySettings(
        batchSize = batchSize,
        claimLeaseMs = claimLeaseMs,
        sendTimeoutMs = sendTimeoutMs,
        maxAttempts = maxAttempts,
        backoffInitialMs = backoffInitialMs,
        backoffMaxMs = backoffMaxMs,
    )
    private val store = TeamOutboxStore(dataSource, meterRegistry, settings, claimOwner)
    private val sender = TeamKafkaSender(kafkaTemplate, objectMapper, meterRegistry, settings, store)

    @Scheduled(
        initialDelayString = "\${kafka.outbox.relay-initial-delay-ms:1000}",
        fixedDelayString = "\${kafka.outbox.relay-delay-ms:1000}",
    )
    fun relayPendingEvents() {
        if (closed.get() || !running.compareAndSet(false, true)) {
            return
        }
        if (closed.get()) {
            running.set(false)
            return
        }

        try {
            var processed = 0
            while (processed < settings.batchSize) {
                if (closed.get() || Thread.currentThread().isInterrupted) {
                    break
                }
                val record = store.claimNext() ?: break
                if (!sender.publish(record)) {
                    break
                }
                processed++
            }
        } catch (exception: Exception) {
            logger.warn("Failed to run Kafka outbox relay for {}", serviceName, exception)
        } finally {
            if (closed.get()) {
                store.releaseOwnedClaims()
            }
            store.refreshMetrics()
            running.set(false)
        }
    }

    @PreDestroy
    fun shutdown() {
        closed.set(true)
        if (!running.get()) {
            store.releaseOwnedClaims()
        }
    }
}
