package com.cowork.project.global.consumer

import com.cowork.project.global.projection.ProjectionRecordProcessor
import com.cowork.project.global.projection.ProjectionStreams
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class UserLifecycleConsumer(
    private val handler: ProjectLifecycleHandler,
    private val objectMapper: ObjectMapper,
    private val processor: ProjectionRecordProcessor,
    private val streams: ProjectionStreams,
) {
    private val log = LoggerFactory.getLogger(UserLifecycleConsumer::class.java)

    @KafkaListener(
        topics = [Topics.USER_LIFECYCLE],
        groupId = "cowork-project.user-lifecycle",
        containerFactory = "userLifecycleListenerContainerFactory",
    )
    fun consume(record: ConsumerRecord<String, String>) {
        if (processor.processControlRecord(streams.userLifecycle, record)) return
        val payload = runCatching { objectMapper.readValue(record.value(), UserLifecyclePayload::class.java) }
            .getOrElse {
                quarantine(record, "user.lifecycle JSON 역직렬화 실패: ${it.message}")
                return
            }
        if (record.key() != payload.userId.toString() || payload.eventType != "USER_DELETED" || payload.userId <= 0) {
            quarantine(record, "지원하지 않거나 식별자가 유효하지 않은 user.lifecycle입니다.")
            return
        }
        processor.applyRecord(streams.userLifecycle, record) {
            handler.onUserDeleted(payload.userId, payload.occurredAt)
        }
    }

    private fun quarantine(record: ConsumerRecord<String, String>, reason: String) {
        log.warn(
            "user.lifecycle을 격리합니다 [partition={}, offset={}, reason={}]",
            record.partition(),
            record.offset(),
            reason,
        )
        processor.quarantineRecord(streams.userLifecycle, record, reason)
    }
}
