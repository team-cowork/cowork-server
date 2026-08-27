package com.cowork.channel.global.consumer

import com.cowork.channel.global.projection.ProjectionRecordProcessor
import com.cowork.channel.global.projection.ProjectionStreams
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class TeamLifecycleConsumer(
    private val handler: ChannelLifecycleHandler,
    private val objectMapper: ObjectMapper,
    private val processor: ProjectionRecordProcessor,
    private val streams: ProjectionStreams,
) {
    private val log = LoggerFactory.getLogger(TeamLifecycleConsumer::class.java)

    @KafkaListener(
        topics = [Topics.TEAM_LIFECYCLE],
        groupId = "cowork-channel.team-lifecycle",
        containerFactory = "teamLifecycleListenerContainerFactory",
    )
    fun consume(record: ConsumerRecord<String, String>) {
        if (processor.processControlRecord(streams.teamLifecycle, record)) return
        val payload = runCatching { objectMapper.readValue(record.value(), TeamLifecyclePayload::class.java) }
            .getOrElse {
                quarantine(record, "team.lifecycle JSON 역직렬화 실패: ${it.message}")
                return
            }
        val knownEvent = payload.eventType in setOf(
            "TEAM_DELETED",
            "MEMBER_INVITED",
            "MEMBER_JOINED",
            "ROLE_CHANGED",
            "MEMBER_REMOVED",
            "TEAM_CREATED",
            "TEAM_UPDATED",
        )
        val reason = when {
            record.key() != payload.teamId.toString() -> "teamId와 Kafka key가 일치하지 않습니다."
            payload.teamId <= 0 -> "teamId는 양수여야 합니다."
            !knownEvent -> "지원하지 않는 eventType입니다."
            payload.occurredAt == null -> "occurredAt이 필요합니다."
            else -> null
        }
        if (reason != null) {
            quarantine(record, reason)
            return
        }
        processor.applyRecord(streams.teamLifecycle, record) {
            if (payload.eventType == "TEAM_DELETED") {
                handler.onTeamDeleted(payload.teamId, requireNotNull(payload.occurredAt))
            }
        }
    }

    private fun quarantine(record: ConsumerRecord<String, String>, reason: String) {
        log.warn(
            "team.lifecycle을 격리합니다 [partition={}, offset={}, reason={}]",
            record.partition(),
            record.offset(),
            reason,
        )
        processor.quarantineRecord(streams.teamLifecycle, record, reason)
    }
}
