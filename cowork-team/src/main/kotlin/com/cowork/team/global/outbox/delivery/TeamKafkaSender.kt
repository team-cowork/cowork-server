package com.cowork.team.global.outbox.delivery

import com.cowork.team.global.outbox.OutboxRelaySettings
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.kafka.core.KafkaTemplate
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.TimeUnit

internal class TeamKafkaSender(
    private val kafkaTemplate: KafkaTemplate<String, Any>,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val settings: OutboxRelaySettings,
    private val store: TeamOutboxStore,
) {
    private val publishTimer =
        Timer
            .builder("cowork.kafka.outbox.publish")
            .description("Kafka outbox publish acknowledgement latency")
            .register(meterRegistry)

    fun publish(record: OutboxRecord): Boolean {
        val payload =
            try {
                deserializePayload(record.payload)
            } catch (exception: Exception) {
                store.finalizeFailure(record, FailureType.PAYLOAD, exception, retryable = false)
                return true
            }

        val sample = Timer.start(meterRegistry)
        return try {
            val sendResult =
                record.partition?.let { partition ->
                    kafkaTemplate.send(record.topic, partition, record.eventKey, payload)
                } ?: kafkaTemplate.send(record.topic, record.eventKey, payload)
            sendResult.get(settings.sendTimeoutMs, TimeUnit.MILLISECONDS)
            store.finalizeSuccess(record)
            true
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            store.releaseClaim(record.id)
            false
        } catch (exception: Exception) {
            store.finalizeFailure(record, FailureType.KAFKA_PUBLISH, exception, retryable = true)
            true
        } finally {
            sample.stop(publishTimer)
        }
    }

    private fun deserializePayload(payload: String): Map<String, Any?> {
        val decoded: Any? = objectMapper.readValue(payload, Any::class.java)
        require(decoded is Map<*, *>) { "Kafka outbox payload must be a JSON object." }
        @Suppress("UNCHECKED_CAST")
        return decoded as Map<String, Any?>
    }
}
