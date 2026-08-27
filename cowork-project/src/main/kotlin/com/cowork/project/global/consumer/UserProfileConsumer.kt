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
class UserProfileConsumer(
    private val handler: UserProfileProjectionHandler,
    private val objectMapper: ObjectMapper,
    private val processor: ProjectionRecordProcessor,
    private val streams: ProjectionStreams,
) {
    private val log = LoggerFactory.getLogger(UserProfileConsumer::class.java)

    @KafkaListener(
        topics = [ProjectionTopics.USER_PROFILE],
        groupId = "cowork-project.user-profile",
        containerFactory = "userProfileListenerContainerFactory",
    )
    fun consume(record: ConsumerRecord<String, String>) {
        if (processor.processControlRecord(streams.userProfile, record)) return
        val payload = runCatching {
            requireNotNull(objectMapper.readValue(record.value(), UserProfilePayload::class.java)) {
                "top-level null은 허용되지 않습니다."
            }
        }
            .getOrElse {
                quarantine(record, "user.profile.event JSON 역직렬화 실패: ${it.message}")
                return
            }
        val occurredAt = payload.occurredAt
        val reason = when {
            record.key() != payload.userId.toString() -> "userId와 Kafka key가 일치하지 않습니다."
            payload.userId <= 0 -> "userId는 양수여야 합니다."
            payload.eventType !in setOf("UPSERT", "DELETE") -> "지원하지 않는 eventType입니다."
            occurredAt == null -> "occurredAt이 필요합니다."
            payload.eventType == "UPSERT" && payload.githubId?.isBlank() == true ->
                "githubId는 빈 문자열일 수 없습니다."
            else -> null
        }
        if (reason != null) {
            quarantine(record, reason)
            return
        }

        processor.applyRecord(streams.userProfile, record) {
            handler.apply(
                userId = payload.userId,
                githubId = payload.githubId.takeUnless { payload.eventType == "DELETE" },
                deleted = payload.eventType == "DELETE",
                occurredAt = requireNotNull(occurredAt),
            )
        }
    }

    private fun quarantine(record: ConsumerRecord<String, String>, reason: String) {
        log.warn(
            "user.profile.event를 격리합니다 [partition={}, offset={}, reason={}]",
            record.partition(),
            record.offset(),
            reason,
        )
        processor.quarantineRecord(streams.userProfile, record, reason)
    }
}
