package com.cowork.preference.global.messaging.command

import com.cowork.preference.global.config.kafkaClientProperties
import com.cowork.preference.global.logging.logger
import com.cowork.preference.global.messaging.command.model.CommandDecision
import com.cowork.preference.global.messaging.command.model.CommandQuarantineRecord
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Applies one command topic record by record and commits each batch only after every record is applied,
 * rejected, or quarantined. Subclasses stay in their domain package so the logger keeps the domain module.
 */
abstract class CommandConsumer<C, E>(
    private val vertx: Vertx,
    bootstrapServers: String,
    private val groupId: String,
    private val topic: String,
    /** Log prefix for consumer events, such as "Channel role policy command". */
    private val label: String,
    /** Command name in rejection and quarantine logs, such as "channel role policy command". */
    private val recordLabel: String,
    private val scope: CoroutineScope,
) {
    private var closed = false
    private val consumer = KafkaConsumer.create<String, String>(
        vertx,
        kafkaClientProperties() + mapOf(
            "bootstrap.servers" to bootstrapServers,
            "key.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
            "value.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
            "group.id" to groupId,
            "auto.offset.reset" to "earliest",
            "enable.auto.commit" to "false",
            "isolation.level" to "read_committed",
        ),
    )

    protected abstract fun parse(key: String?, value: String?): CommandDecision<C, E>

    protected abstract suspend fun applyCommand(command: C)

    protected abstract suspend fun rejectInvalid(envelope: E, record: CommandQuarantineRecord, reason: String)

    protected abstract suspend fun quarantine(record: CommandQuarantineRecord)

    fun start() {
        consumer.exceptionHandler { logger().error("$label consumer failure", it) }
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
            retry("subscribe") { consumer.subscribe(topic).coAwait() }
            this@CommandConsumer.logger().info("$label consumer subscribed topic={} group={}", topic, groupId)
        }
    }

    fun close(): Future<Void> {
        closed = true
        return consumer.close()
    }

    private suspend fun process(records: KafkaConsumerRecords<String, String>) {
        for (index in 0 until records.size()) {
            val record = records.recordAt(index)
            when (val decision = parse(record.key(), record.value())) {
                is CommandDecision.Apply -> retry("apply", record) { applyCommand(decision.command) }

                is CommandDecision.Reject -> {
                    retry("rejection", record) {
                        rejectInvalid(decision.envelope, quarantineRecord(record, decision.reason), decision.reason)
                    }
                    logInvalid("Rejected", record, decision.reason)
                }

                is CommandDecision.Quarantine -> {
                    retry("quarantine", record) { quarantine(quarantineRecord(record, decision.reason)) }
                    logInvalid("Quarantined", record, decision.reason)
                }
            }
        }
    }

    private fun logInvalid(action: String, record: KafkaConsumerRecord<String, String>, reason: String) {
        logger().error(
            "$action invalid $recordLabel topic={} partition={} offset={} reason={}",
            record.topic(),
            record.partition(),
            record.offset(),
            reason,
        )
    }

    private fun quarantineRecord(
        record: KafkaConsumerRecord<String, String>,
        reason: String,
    ): CommandQuarantineRecord = CommandQuarantineRecord(
        topic = record.topic(),
        partition = record.partition(),
        offset = record.offset(),
        key = record.key(),
        payload = record.value(),
        reason = reason,
    )

    private suspend fun <T> retry(
        operation: String,
        record: KafkaConsumerRecord<String, String>? = null,
        block: suspend () -> T,
    ): T {
        while (!closed) {
            try {
                return block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (record == null) {
                    logger().error("$label consumer {} failed; retrying", operation, error)
                } else {
                    logger().error(
                        "$label $operation failed; retrying topic={} partition={} offset={}",
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        error,
                    )
                }
                delay(RETRY_DELAY)
            }
        }
        throw CancellationException("$label consumer stopped")
    }

    private companion object {
        val RETRY_DELAY: Duration = 1.seconds
    }
}
