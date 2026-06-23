package com.cowork.channel.domain.consumer

import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class UserLifecycleConsumer(private val handler: ChannelLifecycleHandler) {
    private val log = LoggerFactory.getLogger(UserLifecycleConsumer::class.java)

    @KafkaListener(
        topics = [Topics.USER_LIFECYCLE],
        groupId = "cowork-channel.user-lifecycle",
        containerFactory = "userLifecycleListenerContainerFactory",
    )
    fun consume(payload: UserLifecyclePayload) {
        when (payload.eventType) {
            "USER_DELETED" -> handler.onUserDeleted(payload.userId)
            else -> log.warn("Received unknown user.lifecycle event [eventType={}]", payload.eventType)
        }
    }
}
