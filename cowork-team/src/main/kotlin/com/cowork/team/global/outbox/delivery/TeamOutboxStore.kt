package com.cowork.team.global.outbox.delivery

import com.cowork.team.global.outbox.OutboxRelaySettings
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource
import kotlin.math.min

internal class TeamOutboxStore(
    private val dataSource: DataSource,
    private val meterRegistry: MeterRegistry,
    private val settings: OutboxRelaySettings,
    private val claimOwner: String,
) {
    private val serviceName = "cowork-team"
    private val logger = LoggerFactory.getLogger(javaClass)
    private val pendingGauge = AtomicLong()
    private val oldestSecondsGauge = AtomicLong()
    private val quarantinedGauge = AtomicLong()
    private val observationSuccessGauge = AtomicLong()
    private val publishedCounter =
        Counter
            .builder("cowork.kafka.outbox.published")
            .description("Kafka outbox rows deleted after publication")
            .register(meterRegistry)
    private val quarantinedCounter =
        Counter
            .builder("cowork.kafka.outbox.quarantined")
            .description("Kafka outbox rows newly quarantined")
            .register(meterRegistry)

    init {
        Gauge
            .builder("cowork.kafka.outbox.pending", pendingGauge) { it.get().toDouble() }
            .description("Shared database Kafka outbox rows waiting for publication")
            .register(meterRegistry)
        Gauge
            .builder("cowork.kafka.outbox.oldest.seconds", oldestSecondsGauge) { it.get().toDouble() }
            .description("Age in seconds of the oldest pending Kafka outbox row")
            .register(meterRegistry)
        Gauge
            .builder("cowork.kafka.outbox.quarantined.current", quarantinedGauge) { it.get().toDouble() }
            .description("Shared database Kafka outbox rows currently quarantined")
            .register(meterRegistry)
        Gauge
            .builder("cowork.kafka.outbox.observation.success", observationSuccessGauge) { it.get().toDouble() }
            .description("Whether the latest Kafka outbox backlog observation succeeded")
            .register(meterRegistry)
    }

    fun claimNext(): OutboxRecord? = dataSource.connection.use { connection ->
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

    fun finalizeSuccess(record: OutboxRecord) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(DELETE_PUBLISHED_SQL).use { statement ->
                statement.setLong(1, record.id)
                statement.setString(2, claimOwner)
                if (statement.executeUpdate() == 1) {
                    publishedCounter.increment()
                } else {
                    logger.warn("Skipped stale Kafka outbox success for row {}", record.id)
                }
            }
        }
    }

    fun finalizeFailure(record: OutboxRecord, failureType: FailureType, exception: Exception, retryable: Boolean) {
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
        meterRegistry.counter(
            "cowork.kafka.outbox.failures",
            "failure_type",
            failureType.metricTag,
        ).increment()
        if (quarantine) {
            quarantinedCounter.increment()
            logger.warn(
                "Quarantined Kafka outbox row {} after {} attempt(s), failureType={}",
                record.id,
                attempts,
                failureType.databaseValue,
            )
        } else {
            meterRegistry.counter(
                "cowork.kafka.outbox.retries",
                "failure_type",
                failureType.metricTag,
            ).increment()
            logger.warn(
                "Scheduled Kafka outbox row {} retry after attempt {}, failureType={}",
                record.id,
                attempts,
                failureType.databaseValue,
            )
        }
    }

    fun releaseClaim(id: Long) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(RELEASE_CLAIM_SQL).use { statement ->
                statement.setLong(1, id)
                statement.setString(2, claimOwner)
                statement.executeUpdate()
            }
        }
    }

    fun releaseOwnedClaims() {
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

    fun refreshMetrics() {
        runCatching {
            dataSource.connection.use { connection ->
                connection.prepareStatement(OBSERVE_SQL).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        check(resultSet.next()) { "Kafka outbox observation returned no row." }
                        pendingGauge.set(resultSet.getLong("pending_count"))
                        oldestSecondsGauge.set(resultSet.getLong("oldest_seconds"))
                        quarantinedGauge.set(resultSet.getLong("quarantined_count"))
                        observationSuccessGauge.set(1)
                    }
                }
            }
        }.onFailure { exception ->
            observationSuccessGauge.set(0)
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

internal data class OutboxRecord(
    val id: Long,
    val topic: String,
    val partition: Int?,
    val eventKey: String,
    val payload: String,
    val attempts: Int,
)

internal enum class FailureType(val databaseValue: String, val metricTag: String) {
    PAYLOAD("PAYLOAD", "payload"),
    KAFKA_PUBLISH("KAFKA_PUBLISH", "kafka_publish"),
}
