package com.cowork.channel.global.outbox

import com.cowork.shared.outbox.JdbcKafkaOutboxRelay
import com.cowork.shared.outbox.OutboxRelaySettings
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import javax.sql.DataSource

@Component
class KafkaOutboxRelay(
    dataSource: DataSource,
    kafkaTemplate: KafkaTemplate<String, Any>,
    objectMapper: ObjectMapper,
    meterRegistry: MeterRegistry,
    @Value("\${kafka.outbox.batch-size:100}") batchSize: Int,
    @Value("\${kafka.outbox.claim-lease-ms:30000}") claimLeaseMs: Long,
    @Value("\${kafka.outbox.send-timeout-ms:10000}") sendTimeoutMs: Long,
    @Value("\${kafka.outbox.max-attempts:8}") maxAttempts: Int,
    @Value("\${kafka.outbox.backoff-initial-ms:5000}") backoffInitialMs: Long,
    @Value("\${kafka.outbox.backoff-max-ms:600000}") backoffMaxMs: Long,
) {
    private val delegate =
        JdbcKafkaOutboxRelay(
            serviceName = "cowork-channel",
            dataSource = dataSource,
            kafkaTemplate = kafkaTemplate,
            objectMapper = objectMapper,
            meterRegistry = meterRegistry,
            settings = OutboxRelaySettings(
                batchSize = batchSize,
                claimLeaseMs = claimLeaseMs,
                sendTimeoutMs = sendTimeoutMs,
                maxAttempts = maxAttempts,
                backoffInitialMs = backoffInitialMs,
                backoffMaxMs = backoffMaxMs,
            ),
        )

    @Scheduled(
        initialDelayString = "\${kafka.outbox.relay-initial-delay-ms:1000}",
        fixedDelayString = "\${kafka.outbox.relay-delay-ms:1000}",
    )
    fun relayPendingEvents() = delegate.relayPendingEvents()

    @PreDestroy
    fun shutdown() = delegate.close()
}
