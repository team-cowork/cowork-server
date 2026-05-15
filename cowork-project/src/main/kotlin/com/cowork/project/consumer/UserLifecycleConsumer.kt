package com.cowork.project.consumer

import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class UserLifecycleConsumer(
    private val handler: ProjectLifecycleHandler,
) {
    private val log = LoggerFactory.getLogger(UserLifecycleConsumer::class.java)

    @KafkaListener(
        topics = [Topics.USER_LIFECYCLE],
        groupId = "cowork-project.user-lifecycle",
        containerFactory = "userLifecycleListenerContainerFactory",
    )
    fun consume(payload: UserLifecyclePayload) {
        when (payload.eventType) {
            "USER_DELETED" -> handler.onUserDeleted(payload.userId)
            else -> log.warn("알 수 없는 user.lifecycle 이벤트 [eventType={}]", payload.eventType)
        }
    }
}
