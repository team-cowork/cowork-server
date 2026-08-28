package com.cowork.preference.messaging

import com.cowork.preference.repository.GithubRepoSettingCommandInboxRepository
import com.cowork.preference.service.GithubRepoSettingCommandProcessor
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

class GithubRepoSettingCommandConsumer(
    private val vertx: Vertx,
    bootstrapServers: String,
    private val groupId: String,
    private val processor: GithubRepoSettingCommandProcessor,
    private val inboxRepository: GithubRepoSettingCommandInboxRepository,
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(GithubRepoSettingCommandConsumer::class.java)
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
        consumer.exceptionHandler { log.error("GitHub repo setting command consumer failure", it) }
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
            retry("subscribe") { consumer.subscribe(PreferenceEvents.GITHUB_REPO_SETTING_COMMAND_TOPIC).coAwait() }
            log.info(
                "GitHub repo setting command consumer subscribed topic={} group={}",
                PreferenceEvents.GITHUB_REPO_SETTING_COMMAND_TOPIC,
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
            when (val decision = GithubRepoSettingCommandParser.parse(record.key(), record.value())) {
                is GithubRepoSettingCommandDecision.Apply -> processWithRetry(record, decision.command)
                is GithubRepoSettingCommandDecision.Reject -> rejectWithRetry(record, decision)
                is GithubRepoSettingCommandDecision.Quarantine -> quarantineWithRetry(record, decision.reason)
            }
        }
    }

    private suspend fun processWithRetry(
        record: KafkaConsumerRecord<String, String>,
        command: GithubRepoSettingCommand,
    ) {
        while (!closed) {
            try {
                processor.process(command)
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.error(
                    "GitHub repo setting command apply failed; retrying topic={} partition={} offset={}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    error,
                )
                delay(RETRY_DELAY_MS)
            }
        }
        throw CancellationException("GitHub repo setting command consumer stopped")
    }

    private suspend fun rejectWithRetry(
        record: KafkaConsumerRecord<String, String>,
        rejection: GithubRepoSettingCommandDecision.Reject,
    ) {
        while (!closed) {
            try {
                processor.rejectInvalid(
                    rejection.envelope,
                    quarantineRecord(record, rejection.reason),
                    rejection.reason,
                )
                log.error(
                    "Rejected invalid GitHub repo setting command topic={} partition={} offset={} reason={}",
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
                    "GitHub repo setting command rejection failed; retrying topic={} partition={} offset={}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    error,
                )
                delay(RETRY_DELAY_MS)
            }
        }
        throw CancellationException("GitHub repo setting command consumer stopped")
    }

    private suspend fun quarantineWithRetry(record: KafkaConsumerRecord<String, String>, reason: String) {
        while (!closed) {
            try {
                inboxRepository.quarantine(quarantineRecord(record, reason))
                log.error(
                    "Quarantined invalid GitHub repo setting command topic={} partition={} offset={} reason={}",
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
                    "GitHub repo setting command quarantine failed; retrying topic={} partition={} offset={}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    error,
                )
                delay(RETRY_DELAY_MS)
            }
        }
        throw CancellationException("GitHub repo setting command consumer stopped")
    }

    private fun quarantineRecord(
        record: KafkaConsumerRecord<String, String>,
        reason: String,
    ): GithubRepoSettingCommandQuarantineRecord = GithubRepoSettingCommandQuarantineRecord(
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
                log.error("GitHub repo setting command consumer {} failed; retrying", operation, error)
                delay(RETRY_DELAY_MS)
            }
        }
        throw CancellationException("GitHub repo setting command consumer stopped")
    }

    private companion object {
        const val RETRY_DELAY_MS = 1_000L
    }
}
