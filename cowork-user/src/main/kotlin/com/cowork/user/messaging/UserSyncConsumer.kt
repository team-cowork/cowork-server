package com.cowork.user.messaging

import com.cowork.user.dto.UpsertUserRequest
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
        val userId = userService.upsertUser(
            UpsertUserRequest(
                userId = event.userId,
                name = event.name,
                email = event.email,
                sex = event.sex,
                grade = event.grade,
                `class` = event.`class`,
                classNum = event.classNum,
                major = event.major,
                role = event.role,
                githubId = event.githubId,
            )
        )
        log.info("UserProfile upserted. userId={}", userId)
    }
}
