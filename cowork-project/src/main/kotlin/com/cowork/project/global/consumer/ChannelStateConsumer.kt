package com.cowork.project.global.consumer

import com.cowork.project.global.projection.ProjectionRecordProcessor
import com.cowork.project.global.projection.ProjectionStreams
import com.cowork.project.global.projection.ProjectionTopics
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class ChannelStateConsumer(
    private val handler: ChannelProjectionHandler,
    private val objectMapper: ObjectMapper,
    private val processor: ProjectionRecordProcessor,
    private val streams: ProjectionStreams,
) {
    private val log = LoggerFactory.getLogger(ChannelStateConsumer::class.java)

    @KafkaListener(
        topics = [ProjectionTopics.CHANNEL_STATE],
        groupId = "cowork-project.channel-state",
        containerFactory = "channelStateListenerContainerFactory",
    )
    fun consume(record: ConsumerRecord<String, String>) {
        if (processor.processControlRecord(streams.channelState, record)) return
        val payload = runCatching {
            requireNotNull(objectMapper.readValue(record.value(), ChannelStatePayload::class.java)) {
                "top-level null은 허용되지 않습니다."
            }
        }
            .getOrElse {
                quarantine(record, "channel.event JSON 역직렬화 실패: ${it.message}")
                return
            }
        val occurredAt = payload.occurredAt
        val reason = when {
            record.key() != payload.channelId.toString() -> "channelId와 Kafka key가 일치하지 않습니다."
            payload.channelId <= 0 -> "channelId는 양수여야 합니다."
            payload.projectId != null && payload.projectId <= 0 -> "projectId는 null이거나 양수여야 합니다."
            payload.eventType !in setOf("CREATED", "UPDATED", "DELETED") -> "지원하지 않는 eventType입니다."
            occurredAt == null -> "occurredAt이 필요합니다."
            else -> null
        }
        if (reason != null) {
            quarantine(record, reason)
            return
        }

        processor.applyRecord(streams.channelState, record) {
            handler.apply(
                channelId = payload.channelId,
                projectId = payload.projectId,
                deleted = payload.eventType == "DELETED",
                occurredAt = requireNotNull(occurredAt),
            )
        }
    }

    private fun quarantine(record: ConsumerRecord<String, String>, reason: String) {
        log.warn(
            "channel.event를 격리합니다 [partition={}, offset={}, reason={}]",
            record.partition(),
            record.offset(),
            reason,
        )
        processor.quarantineRecord(streams.channelState, record, reason)
    }
}
