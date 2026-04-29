package com.cowork.team.event

import com.cowork.team.dto.TeamEventPayload
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
class TeamEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, TeamEventPayload>,
) {
    private val log = LoggerFactory.getLogger(TeamEventPublisher::class.java)

    fun publishNotification(payload: TeamEventPayload) = send(Topics.NOTIFICATION_TRIGGER, payload)

    fun publishLifecycle(payload: TeamEventPayload) = send(Topics.TEAM_LIFECYCLE, payload)

    private fun send(topic: String, payload: TeamEventPayload) {
        kafkaTemplate.send(topic, payload.teamId.toString(), payload)
            .whenComplete { result, ex ->
                if (ex != null) {
                    log.error(
                        "Kafka 이벤트 발행 실패 [topic={}, eventType={}, teamId={}]",
                        topic, payload.eventType, payload.teamId, ex,
                    )
                } else {
                    log.info(
                        "Kafka 이벤트 발행 성공 [topic={}, eventType={}, offset={}]",
                        topic, payload.eventType, result.recordMetadata.offset(),
                    )
                }
            }
    }
}
