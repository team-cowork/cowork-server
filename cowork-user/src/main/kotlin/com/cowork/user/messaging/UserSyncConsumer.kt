package com.cowork.user.messaging

import com.cowork.user.messaging.event.UserCreatedEvent
import com.cowork.user.service.UserService
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class UserSyncConsumer(
    private val userService: UserService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["user.data.sync"], groupId = "cowork-user")
    fun handleUserCreated(event: UserCreatedEvent) {
        val response = userService.upsertUserFromSyncEvent(event)
        log.info("User profile upserted. userId={}", response.id)
    }
}
