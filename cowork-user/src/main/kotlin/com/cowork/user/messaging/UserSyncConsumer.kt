package com.cowork.user.messaging

import com.cowork.user.domain.Account
import com.cowork.user.domain.Profile
import com.cowork.user.messaging.event.UserCreatedEvent
import com.cowork.user.repository.AccountRepository
import com.cowork.user.repository.ProfileRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class UserSyncConsumer(
    private val accountRepository: AccountRepository,
    private val profileRepository: ProfileRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    @KafkaListener(topics = ["user.data.sync"], groupId = "cowork-user")
    fun handleUserCreated(event: UserCreatedEvent) {
        if (accountRepository.existsById(event.userId)) {
            log.warn("Account already exists, skipping. userId={}", event.userId)
            return
        }

        val account = accountRepository.save(
            Account(
                id = event.userId,
                name = event.name,
                email = event.email,
                sex = event.sex,
                github = event.github,
                description = event.description,
                studentRole = event.studentRole,
                studentNumber = event.studentNumber,
                major = event.major,
                specialty = event.specialty,
                status = event.status ?: "offline",
            ),
        )

        profileRepository.save(
            Profile(
                account = account,
                profileImageKey = null,
                nickname = null,
                description = null,
                roles = mutableSetOf(),
            ),
        )

        log.info("Account and Profile created. userId={}", event.userId)
    }
}
