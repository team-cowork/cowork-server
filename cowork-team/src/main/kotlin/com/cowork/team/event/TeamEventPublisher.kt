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
    private val topic = "notification.trigger"

    fun publish(payload: TeamEventPayload) {
        kafkaTemplate.send(topic, payload.teamId.toString(), payload)
            .whenComplete { result, ex ->
                if (ex != null) {
                    log.error("Kafka 이벤트 발행 실패 [eventType=${payload.eventType}, teamId=${payload.teamId}]", ex)
                } else {
                    log.info("Kafka 이벤트 발행 성공 [eventType=${payload.eventType}, offset=${result.recordMetadata.offset()}]")
                }
            }
    }
}
