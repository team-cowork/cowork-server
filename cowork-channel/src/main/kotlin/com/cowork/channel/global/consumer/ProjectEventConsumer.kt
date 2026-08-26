package com.cowork.channel.global.consumer

import com.cowork.channel.global.projection.ProjectionRecordProcessor
import com.cowork.channel.global.projection.ProjectionStreams
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class ProjectEventConsumer(
    private val handler: ProjectProjectionHandler,
    private val objectMapper: ObjectMapper,
    private val processor: ProjectionRecordProcessor,
    private val streams: ProjectionStreams,
) {
    private val log = LoggerFactory.getLogger(ProjectEventConsumer::class.java)

    @KafkaListener(
        topics = [Topics.PROJECT_EVENT],
        groupId = "cowork-channel.project-event",
        containerFactory = "projectEventListenerContainerFactory",
    )
    fun consume(record: ConsumerRecord<String, String>) {
        if (processor.processControlRecord(streams.project, record)) return
        val payload = runCatching { objectMapper.readValue(record.value(), ProjectEventPayload::class.java) }
            .getOrElse {
                quarantine(record, "project.event JSON 역직렬화 실패: ${it.message}")
                return
            }
        val reason = contractViolation(payload, record.key())
        if (reason != null) {
            quarantine(record, reason)
            return
        }
        processor.applyRecord(streams.project, record) { handler.apply(payload) }
    }

    private fun contractViolation(payload: ProjectEventPayload, key: String?): String? = when {
        key != payload.projectId.toString() -> "projectId와 Kafka key가 일치하지 않습니다."
        payload.projectId <= 0 || payload.teamId <= 0 -> "projectId와 teamId는 양수여야 합니다."
        payload.occurredAt == null -> "occurredAt이 필요합니다."
        payload.eventType !in setOf("CREATED", "UPDATED", "DELETED") -> "지원하지 않는 eventType입니다."
        else -> null
    }

    private fun quarantine(record: ConsumerRecord<String, String>, reason: String) {
        log.warn(
            "project.event를 격리합니다 [partition={}, offset={}, reason={}]",
            record.partition(),
            record.offset(),
            reason,
        )
        processor.quarantineRecord(streams.project, record, reason)
    }
}
