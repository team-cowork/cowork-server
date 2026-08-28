package com.cowork.team.global.outbox

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.sql.Connection
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@Component
class KafkaOutboxRelay(
    private val dataSource: DataSource,
    @Qualifier("teamGithubDlqKafkaTemplate")
    private val kafkaTemplate: KafkaTemplate<String, Any>,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        initialDelayString = "\${kafka.outbox.relay-initial-delay-ms:1000}",
        fixedDelayString = "\${kafka.outbox.relay-delay-ms:1000}",
    )
    fun relayPendingEvents() {
        try {
            dataSource.connection.use { connection ->
                connection.autoCommit = true
                if (!tryAcquireLock(connection)) {
                    return@use
                }

                try {
                    relayInTransaction(connection)
                } finally {
                    releaseLock(connection)
                }
            }
        } catch (exception: Throwable) {
            logger.warn("Failed to run Kafka outbox relay", exception)
        }
    }

    private fun relayInTransaction(connection: Connection) {
        connection.autoCommit = false
        try {
            relayBatch(connection)
            connection.commit()
        } catch (exception: Throwable) {
            runCatching(connection::rollback).onFailure(exception::addSuppressed)
            throw exception
        } finally {
            connection.autoCommit = true
        }
    }

    private fun relayBatch(connection: Connection) {
        for (record in findPending(connection)) {
            try {
                val payload = deserializePayload(record.payload)
                val sendResult = record.partition?.let { partition ->
                    kafkaTemplate.send(record.topic, partition, record.eventKey, payload)
                } ?: kafkaTemplate.send(record.topic, record.eventKey, payload)
                sendResult
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                deletePublished(connection, record.id)
            } catch (exception: Exception) {
                if (exception is InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                markFailed(connection, record.id, exception)
                logger.warn("Failed to relay Kafka outbox row {}", record.id, exception)
                break
            }
        }
    }

    private fun deserializePayload(payload: String): Map<String, Any?> {
        val decoded: Any? = objectMapper.readValue(payload, Any::class.java)
        require(decoded is Map<*, *>) { "Kafka outbox payload must be a JSON object." }
        @Suppress("UNCHECKED_CAST")
        return decoded as Map<String, Any?>
    }

    private fun findPending(connection: Connection): List<OutboxRecord> =
        connection.prepareStatement(FIND_PENDING_SQL).use { statement ->
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(
                            OutboxRecord(
                                id = resultSet.getLong("id"),
                                topic = resultSet.getString("topic"),
                                partition = resultSet.getInt("partition_id").let {
                                    if (resultSet.wasNull()) null else it
                                },
                                eventKey = resultSet.getString("event_key"),
                                payload = resultSet.getString("payload"),
                            ),
                        )
                    }
                }
            }
        }

    private fun deletePublished(connection: Connection, id: Long) {
        connection.prepareStatement("DELETE FROM tb_kafka_outbox WHERE id = ?").use { statement ->
            statement.setLong(1, id)
            check(statement.executeUpdate() == 1) { "Published outbox row was not deleted." }
        }
    }

    private fun markFailed(connection: Connection, id: Long, exception: Exception) {
        connection.prepareStatement(
            "UPDATE tb_kafka_outbox SET attempts = attempts + 1, last_error = ? WHERE id = ?",
        ).use { statement ->
            statement.setString(1, failureMessage(exception))
            statement.setLong(2, id)
            check(statement.executeUpdate() == 1) { "Failed outbox row was not updated." }
        }
    }

    private fun tryAcquireLock(connection: Connection): Boolean =
        connection.prepareStatement("SELECT GET_LOCK(?, 0)").use { statement ->
            statement.setString(1, LOCK_NAME)
            statement.executeQuery().use { resultSet ->
                resultSet.next() && resultSet.getInt(1) == 1 && !resultSet.wasNull()
            }
        }

    private fun releaseLock(connection: Connection) {
        connection.prepareStatement("SELECT RELEASE_LOCK(?)").use { statement ->
            statement.setString(1, LOCK_NAME)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next() && resultSet.getInt(1) == 1 && !resultSet.wasNull()) {
                    "Kafka outbox relay lock was not released."
                }
            }
        }
    }

    private fun failureMessage(exception: Exception): String {
        val rootCause = generateSequence<Throwable>(exception) { it.cause }.last()
        val detail = rootCause.message?.takeIf { it.isNotBlank() } ?: "No failure message"
        return "${rootCause.javaClass.simpleName}: $detail".take(MAX_ERROR_LENGTH)
    }

    private data class OutboxRecord(
        val id: Long,
        val topic: String,
        val partition: Int?,
        val eventKey: String,
        val payload: String,
    )

    private companion object {
        const val LOCK_NAME = "cowork-team:kafka-outbox"
        const val SEND_TIMEOUT_SECONDS = 10L
        const val MAX_ERROR_LENGTH = 8_000
        const val FIND_PENDING_SQL =
            "SELECT id, topic, partition_id, event_key, payload " +
                "FROM tb_kafka_outbox ORDER BY id ASC LIMIT 100 FOR UPDATE"
    }
}
