package com.cowork.channel.global.consumer

import com.cowork.channel.domain.thread.event.ThreadParentContract
import com.cowork.channel.domain.thread.event.ThreadParentResult
import com.cowork.channel.domain.thread.service.ResolveThreadParentService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class ThreadParentResultConsumer(
    private val resolveThreadParentService: ResolveThreadParentService,
    private val objectMapper: ObjectMapper,
) {
    @KafkaListener(
        topics = [ThreadParentContract.RESULT_TOPIC],
        groupId = "cowork-channel.thread-parent-result",
        containerFactory = "threadParentResultListenerContainerFactory",
    )
    fun consume(record: ConsumerRecord<String, String>) {
        resolveThreadParentService.resolve(parse(record))
    }

    internal fun parse(record: ConsumerRecord<String, String>): ThreadParentResult {
        val result = runCatching {
            objectMapper.readValue(record.value(), ThreadParentResult::class.java)
        }.getOrElse { throw IllegalArgumentException("thread parent result JSON이 유효하지 않습니다.", it) }
        require(result.schemaVersion == ThreadParentContract.SCHEMA_VERSION) {
            "지원하지 않는 thread parent result schemaVersion입니다."
        }
        require(record.key() == result.threadId.toString()) { "thread parent result key가 threadId와 일치하지 않습니다." }
        require(result.threadId > 0 && result.channelId > 0) { "thread parent result 식별자가 유효하지 않습니다." }
        require(result.accepted == (result.reason == null)) { "thread parent result reason이 accepted와 맞지 않습니다." }
        return result
    }
}
