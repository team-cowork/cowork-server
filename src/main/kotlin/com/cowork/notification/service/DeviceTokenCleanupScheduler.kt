package com.cowork.notification.service

import com.cowork.notification.repository.DeviceTokenRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

/**
 * 오랫동안 다시 등록되지 않은 기기 토큰을 지운다. 앱을 지운 기기로 푸시를 계속 보내지 않기 위해서다.
 */
@Component
class DeviceTokenCleanupScheduler(
    private val deviceTokenRepository: DeviceTokenRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 30 4 * * *")
    @Transactional
    fun deleteStaleTokens() {
        val deleted = deviceTokenRepository.deleteAllRegisteredBefore(LocalDateTime.now(clock).minusDays(STALE_DAYS))
        if (deleted > 0) {
            log.info("Delete {} stale device tokens", deleted)
        }
    }

    companion object {
        private const val STALE_DAYS = 60L
    }
}
