package com.cowork.auth.service

import com.cowork.auth.repository.RefreshTokenRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

@Component
class RefreshTokenCleanupScheduler(
    private val refreshTokenRepository: RefreshTokenRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 4 * * *")
    @Transactional
    fun deleteExpiredTokens() {
        val deleted = refreshTokenRepository.deleteExpired(LocalDateTime.now(clock))
        log.info("Delete {} expired refresh tokens", deleted)
    }
}
