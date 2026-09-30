package com.cowork.project.global.outbox.flow

import com.cowork.project.global.outbox.telemetry.ProjectOutboxTelemetry
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import kotlin.math.min

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
    private val dataSource: DataSource,
    private val kafkaTemplate: KafkaTemplate<String, Any>,
    private val objectMapper: ObjectMapper,
    meterRegistry: MeterRegistry,
    @Value("\${kafka.outbox.batch-size:100}") batchSize: Int,
    @Value("\${kafka.outbox.claim-lease-ms:30000}") claimLeaseMs: Long,
    @Value("\${kafka.outbox.send-timeout-ms:10000}") sendTimeoutMs: Long,
    @Value("\${kafka.outbox.max-attempts:8}") maxAttempts: Int,
    @Value("\${kafka.outbox.backoff-initial-ms:5000}") backoffInitialMs: Long,
    @Value("\${kafka.outbox.backoff-max-ms:600000}") backoffMaxMs: Long,
) {
    private val serviceName = "cowork-project"
    private val settings = OutboxRelaySettings(
        batchSize = batchSize,
        claimLeaseMs = claimLeaseMs,
        sendTimeoutMs = sendTimeoutMs,
        maxAttempts = maxAttempts,
        backoffInitialMs = backoffInitialMs,
        backoffMaxMs = backoffMaxMs,
    )
    private val telemetry = ProjectOutboxTelemetry(meterRegistry)
    private val logger = LoggerFactory.getLogger(javaClass)
    private val claimOwner = "$serviceName:${UUID.randomUUID()}"
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

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
            val pending = generateSequence {
                if (closed.get() || Thread.currentThread().isInterrupted) null else claimNext()
            }.take(settings.batchSize)
            for (record in pending) {
                if (!publish(record)) {
                    break
                }
            }
        } catch (exception: Exception) {
            logger.warn("Failed to run Kafka outbox relay for {}", serviceName, exception)
        } finally {
            if (closed.get()) {
                releaseOwnedClaims()
            }
            refreshMetrics()
            running.set(false)
        }
    }

    @PreDestroy
    fun shutdown() {
        closed.set(true)
        if (!running.get()) {
            releaseOwnedClaims()
        }
    }

    private fun claimNext(): OutboxRecord? = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            lockProducerFence(connection)
            val record = selectNextPending(connection)
            if (record != null) {
                claim(connection, record.id)
            }
            connection.commit()
            record
        } catch (exception: Exception) {
            runCatching(connection::rollback).onFailure(exception::addSuppressed)
            throw exception
        } finally {
            connection.autoCommit = true
        }
    }

    private fun lockProducerFence(connection: Connection) {
        connection.prepareStatement(LOCK_FENCE_SQL).use { statement ->
            statement.executeQuery().use { resultSet ->
                check(resultSet.next() && resultSet.getLong(1) == FENCE_ID) {
                    "Kafka outbox producer fence is missing."
                }
            }
        }
    }

    private fun selectNextPending(connection: Connection): OutboxRecord? =
        connection.prepareStatement(FIND_NEXT_SQL).use { statement ->
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    null
                } else {
                    resultSet.toOutboxRecord()
                }
            }
        }

    private fun claim(connection: Connection, id: Long) {
        connection.prepareStatement(CLAIM_SQL).use { statement ->
            statement.setString(1, claimOwner)
            statement.setLong(2, settings.claimLeaseMs * MICROS_PER_MILLISECOND)
            statement.setLong(3, id)
            check(statement.executeUpdate() == 1) { "Kafka outbox row was not claimed." }
        }
    }

    private fun publish(record: OutboxRecord): Boolean {
        val payload =
            try {
                deserializePayload(record.payload)
            } catch (exception: Exception) {
                finalizeFailure(record, FailureType.PAYLOAD, exception, retryable = false)
                return true
            }

        val sample = telemetry.startPublish()
        return try {
            val sendResult =
                record.partition?.let { partition ->
                    kafkaTemplate.send(record.topic, partition, record.eventKey, payload)
                } ?: kafkaTemplate.send(record.topic, record.eventKey, payload)
            sendResult.get(settings.sendTimeoutMs, TimeUnit.MILLISECONDS)
            finalizeSuccess(record)
            true
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            releaseClaim(record.id)
            false
        } catch (exception: Exception) {
            finalizeFailure(record, FailureType.KAFKA_PUBLISH, exception, retryable = true)
            true
        } finally {
            telemetry.stopPublish(sample)
        }
    }

    private fun deserializePayload(payload: String): Map<String, Any?> {
        val decoded: Any? = objectMapper.readValue(payload, Any::class.java)
        require(decoded is Map<*, *>) { "Kafka outbox payload must be a JSON object." }
        @Suppress("UNCHECKED_CAST")
        return decoded as Map<String, Any?>
    }

    private fun finalizeSuccess(record: OutboxRecord) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(DELETE_PUBLISHED_SQL).use { statement ->
                statement.setLong(1, record.id)
                statement.setString(2, claimOwner)
                if (statement.executeUpdate() == 1) {
                    telemetry.published()
                } else {
                    logger.warn("Skipped stale Kafka outbox success for row {}", record.id)
                }
            }
        }
    }

    private fun finalizeFailure(
        record: OutboxRecord,
        failureType: FailureType,
        exception: Exception,
        retryable: Boolean,
    ) {
        val attempts = record.attempts + 1
        val quarantine = !retryable || attempts >= settings.maxAttempts
        val updated =
            dataSource.connection.use { connection ->
                val sql = if (quarantine) QUARANTINE_SQL else RETRY_SQL
                connection.prepareStatement(sql).use { statement ->
                    statement.setInt(1, attempts)
                    statement.setString(2, failureType.databaseValue)
                    statement.setString(3, failureMessage(exception, includeDetail = retryable))
                    var parameter = 4
                    if (!quarantine) {
                        statement.setLong(parameter++, retryDelayMillis(attempts) * MICROS_PER_MILLISECOND)
                    }
                    statement.setLong(parameter++, record.id)
                    statement.setString(parameter, claimOwner)
                    statement.executeUpdate() == 1
                }
            }

        if (!updated) {
            logger.warn("Skipped stale Kafka outbox failure for row {}", record.id)
            return
        }
        telemetry.failure(failureType.metricTag)
        if (quarantine) {
            telemetry.quarantined()
            logger.warn(
                "Quarantined Kafka outbox row {} after {} attempt(s), failureType={}",
                record.id,
                attempts,
                failureType.databaseValue,
            )
        } else {
            telemetry.retry(failureType.metricTag)
            logger.warn(
                "Scheduled Kafka outbox row {} retry after attempt {}, failureType={}",
                record.id,
                attempts,
                failureType.databaseValue,
            )
        }
    }

    private fun releaseClaim(id: Long) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(RELEASE_CLAIM_SQL).use { statement ->
                statement.setLong(1, id)
                statement.setString(2, claimOwner)
                statement.executeUpdate()
            }
        }
    }

    private fun releaseOwnedClaims() {
        runCatching {
            dataSource.connection.use { connection ->
                connection.prepareStatement(RELEASE_OWNED_CLAIMS_SQL).use { statement ->
                    statement.setString(1, claimOwner)
                    statement.executeUpdate()
                }
            }
        }.onFailure { exception ->
            logger.warn("Failed to release Kafka outbox claims for {}", serviceName, exception)
        }
    }

    private fun refreshMetrics() {
        runCatching {
            dataSource.connection.use { connection ->
                connection.prepareStatement(OBSERVE_SQL).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        check(resultSet.next()) { "Kafka outbox observation returned no row." }
                        telemetry.observe(
                            pending = resultSet.getLong("pending_count"),
                            oldestSeconds = resultSet.getLong("oldest_seconds"),
                            quarantined = resultSet.getLong("quarantined_count"),
                        )
                    }
                }
            }
        }.onFailure { exception ->
            telemetry.observationFailed()
            logger.warn("Failed to observe Kafka outbox backlog for {}", serviceName, exception)
        }
    }

    private fun retryDelayMillis(attempts: Int): Long {
        var delay = settings.backoffInitialMs
        repeat(attempts - 1) {
            delay =
                if (delay >= settings.backoffMaxMs / 2) {
                    settings.backoffMaxMs
                } else {
                    min(settings.backoffMaxMs, delay * 2)
                }
        }
        val jitter = delay / JITTER_DIVISOR
        if (jitter == 0L) {
            return delay
        }
        return delay + ThreadLocalRandom.current().nextLong(-jitter, jitter + 1)
    }

    private fun failureMessage(exception: Exception, includeDetail: Boolean): String {
        val rootCause = generateSequence<Throwable>(exception) { it.cause }.last()
        if (!includeDetail) {
            return rootCause.javaClass.simpleName.take(MAX_ERROR_LENGTH)
        }
        val detail = rootCause.message?.takeIf { it.isNotBlank() } ?: "No failure message"
        return "${rootCause.javaClass.simpleName}: $detail".take(MAX_ERROR_LENGTH)
    }

    private fun ResultSet.toOutboxRecord(): OutboxRecord = OutboxRecord(
        id = getLong("id"),
        topic = getString("topic"),
        partition = getInt("partition_id").let { if (wasNull()) null else it },
        eventKey = getString("event_key"),
        payload = getString("payload"),
        attempts = getInt("attempts"),
    )

    private data class OutboxRecord(
        val id: Long,
        val topic: String,
        val partition: Int?,
        val eventKey: String,
        val payload: String,
        val attempts: Int,
    )

    private enum class FailureType(val databaseValue: String, val metricTag: String) {
        PAYLOAD("PAYLOAD", "payload"),
        KAFKA_PUBLISH("KAFKA_PUBLISH", "kafka_publish"),
    }

    private companion object {
        const val FENCE_ID = 1L
        const val MICROS_PER_MILLISECOND = 1_000L
        const val JITTER_DIVISOR = 5L
        const val MAX_ERROR_LENGTH = 8_000
        const val LOCK_FENCE_SQL = "SELECT id FROM tb_kafka_outbox_fence WHERE id = 1 FOR UPDATE"
        const val FIND_NEXT_SQL =
            """
            SELECT candidate.id,
                   candidate.topic,
                   candidate.partition_id,
                   candidate.event_key,
                   candidate.payload,
                   candidate.attempts
            FROM tb_kafka_outbox candidate
            WHERE candidate.status = 'PENDING'
              AND candidate.next_attempt_at <= CURRENT_TIMESTAMP(6)
              AND (candidate.claim_until IS NULL OR candidate.claim_until <= CURRENT_TIMESTAMP(6))
              AND NOT EXISTS (
                    SELECT 1
                    FROM tb_kafka_outbox earlier_key
                    WHERE earlier_key.topic = candidate.topic
                      AND earlier_key.event_key = candidate.event_key
                      AND earlier_key.id < candidate.id
              )
              AND (
                    candidate.is_barrier = FALSE
                    OR NOT EXISTS (
                        SELECT 1
                        FROM tb_kafka_outbox earlier
                        WHERE earlier.id < candidate.id
                    )
              )
            ORDER BY candidate.id ASC
            LIMIT 1
            FOR UPDATE
            """
        const val CLAIM_SQL =
            "UPDATE tb_kafka_outbox " +
                "SET claim_owner = ?, " +
                "claim_until = TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(6)) " +
                "WHERE id = ? AND status = 'PENDING'"
        const val DELETE_PUBLISHED_SQL =
            "DELETE FROM tb_kafka_outbox " +
                "WHERE id = ? AND claim_owner = ? AND claim_until > CURRENT_TIMESTAMP(6)"
        const val RETRY_SQL =
            "UPDATE tb_kafka_outbox " +
                "SET attempts = ?, failure_type = ?, last_error = ?, " +
                "next_attempt_at = TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(6)), " +
                "claim_owner = NULL, claim_until = NULL " +
                "WHERE id = ? AND claim_owner = ? AND claim_until > CURRENT_TIMESTAMP(6)"
        const val QUARANTINE_SQL =
            "UPDATE tb_kafka_outbox " +
                "SET status = 'QUARANTINED', attempts = ?, failure_type = ?, last_error = ?, " +
                "next_attempt_at = NULL, claim_owner = NULL, claim_until = NULL " +
                "WHERE id = ? AND claim_owner = ? AND claim_until > CURRENT_TIMESTAMP(6)"
        const val RELEASE_CLAIM_SQL =
            "UPDATE tb_kafka_outbox SET claim_owner = NULL, claim_until = NULL, " +
                "next_attempt_at = CURRENT_TIMESTAMP(6) WHERE id = ? AND claim_owner = ?"
        const val RELEASE_OWNED_CLAIMS_SQL =
            "UPDATE tb_kafka_outbox SET claim_owner = NULL, claim_until = NULL, " +
                "next_attempt_at = CURRENT_TIMESTAMP(6) WHERE claim_owner = ? AND status = 'PENDING'"
        const val OBSERVE_SQL =
            """
            SELECT COALESCE(SUM(status = 'PENDING'), 0) AS pending_count,
                   COALESCE(
                       MAX(
                           CASE WHEN status = 'PENDING'
                               THEN TIMESTAMPDIFF(SECOND, created_at, CURRENT_TIMESTAMP(6))
                               ELSE 0
                           END
                       ),
                       0
                   ) AS oldest_seconds,
                   COALESCE(SUM(status = 'QUARANTINED'), 0) AS quarantined_count
            FROM tb_kafka_outbox
            """
    }
}
