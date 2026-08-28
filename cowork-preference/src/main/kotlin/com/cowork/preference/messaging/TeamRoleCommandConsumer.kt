package com.cowork.preference.messaging

import com.cowork.preference.repository.TeamRoleCommandInboxRepository
import com.cowork.preference.service.TeamRoleCommandProcessor
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.kafka.client.consumer.KafkaConsumer
import io.vertx.kafka.client.consumer.KafkaConsumerRecord
import io.vertx.kafka.client.consumer.KafkaConsumerRecords
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class TeamRoleCommandConsumer(
    private val vertx: Vertx,
    bootstrapServers: String,
    private val groupId: String,
    private val processor: TeamRoleCommandProcessor,
    private val inboxRepository: TeamRoleCommandInboxRepository,
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(TeamRoleCommandConsumer::class.java)
    private var closed = false
    private val consumer = KafkaConsumer.create<String, String>(
        vertx,
        mapOf(
            "bootstrap.servers" to bootstrapServers,
            "key.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
            "value.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
            "group.id" to groupId,
            "auto.offset.reset" to "earliest",
            "enable.auto.commit" to "false",
            "isolation.level" to "read_committed",
        ),
    )

    fun start() {
        consumer.exceptionHandler { log.error("preference team-role command consumer failure", it) }
        consumer.batchHandler { records ->
            consumer.pause()
            scope.launch(vertx.dispatcher()) {
                try {
                    process(records)
                    consumer.commit().coAwait()
                } finally {
                    if (!closed) consumer.resume()
                }
            }
        }
        scope.launch(vertx.dispatcher()) {
            retry("subscribe") { consumer.subscribe(PreferenceEvents.TEAM_ROLE_COMMAND_TOPIC).coAwait() }
            log.info(
                "Preference team-role command consumer subscribed topic={} group={}",
                PreferenceEvents.TEAM_ROLE_COMMAND_TOPIC,
                groupId,
            )
        }
    }

    fun close(): Future<Void> {
        closed = true
        return consumer.close()
    }

    private suspend fun process(records: KafkaConsumerRecords<String, String>) {
        for (index in 0 until records.size()) {
            val record = records.recordAt(index)
            when (val decision = TeamRoleCommandParser.parse(record.key(), record.value())) {
                is TeamRoleCommandRecordDecision.Apply -> processWithRetry(record, decision.command)
                is TeamRoleCommandRecordDecision.Reject -> rejectWithRetry(record, decision)
                is TeamRoleCommandRecordDecision.Quarantine -> quarantineWithRetry(record, decision.reason)
            }
        }
    }

    private suspend fun processWithRetry(record: KafkaConsumerRecord<String, String>, command: TeamRoleCommand) {
        while (!closed) {
            try {
                processor.process(command)
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.error(
                    "Preference team-role command apply failed; retrying topic={} partition={} offset={}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    error,
                )
                delay(RETRY_DELAY_MS)
            }
        }
        throw CancellationException("preference team-role command consumer stopped")
    }

    private suspend fun rejectWithRetry(
        record: KafkaConsumerRecord<String, String>,
        rejection: TeamRoleCommandRecordDecision.Reject,
    ) {
        while (!closed) {
            try {
                processor.rejectInvalid(
                    rejection.envelope,
                    quarantineRecord(record, rejection.reason),
                    rejection.reason,
                )
                log.error(
                    "Rejected invalid team-role command topic={} partition={} offset={} reason={}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    rejection.reason,
                )
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.error(
                    "Preference team-role command rejection failed; retrying topic={} partition={} offset={}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    error,
                )
                delay(RETRY_DELAY_MS)
            }
        }
        throw CancellationException("preference team-role command consumer stopped")
    }

    private suspend fun quarantineWithRetry(record: KafkaConsumerRecord<String, String>, reason: String) {
        while (!closed) {
            try {
                inboxRepository.quarantine(quarantineRecord(record, reason))
                log.error(
                    "Quarantined invalid team-role command topic={} partition={} offset={} reason={}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    reason,
                )
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.error(
                    "Preference team-role command quarantine failed; retrying topic={} partition={} offset={}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    error,
                )
                delay(RETRY_DELAY_MS)
            }
        }
        throw CancellationException("preference team-role command consumer stopped")
    }

    private fun quarantineRecord(
        record: KafkaConsumerRecord<String, String>,
        reason: String,
    ): TeamRoleCommandQuarantineRecord = TeamRoleCommandQuarantineRecord(
        topic = record.topic(),
        partition = record.partition(),
        offset = record.offset(),
        key = record.key(),
        payload = record.value(),
        reason = reason,
    )

    private suspend fun <T> retry(operation: String, block: suspend () -> T): T {
        while (!closed) {
            try {
                return block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.error("Preference team-role command consumer {} failed; retrying", operation, error)
                delay(RETRY_DELAY_MS)
            }
        }
        throw CancellationException("preference team-role command consumer stopped")
    }

    private companion object {
        const val RETRY_DELAY_MS = 1_000L
    }
}
