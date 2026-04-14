package com.cowork.user.messaging

import com.cowork.user.domain.UserProfile
import com.cowork.user.messaging.event.UserCreatedEvent
import com.cowork.user.repository.UserProfileRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class UserSyncConsumer(
    private val userProfileRepository: UserProfileRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    @KafkaListener(topics = ["user.data.sync"], groupId = "cowork-user")
    fun handleUserCreated(event: UserCreatedEvent) {
        if (userProfileRepository.existsById(event.userId)) {
            log.warn("UserProfile already exists, skipping. userId={}", event.userId)
            return
        }

        val profile = UserProfile(
            id = event.userId,
            name = event.name,
            email = event.email,
            sex = event.sex,
            grade = event.grade,
            `class` = event.`class`,
            classNum = event.classNum,
            major = event.major,
            role = event.role,
            githubId = event.githubId,
            specialty = null,
            profileImageKey = null,
        )

        userProfileRepository.save(profile)
        log.info("UserProfile created. userId={}", event.userId)
    }
}