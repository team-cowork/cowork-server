package com.cowork.channel.account.service

import com.cowork.channel.account.repository.CredentialCopyHistoryRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

/**
 * 1년이 지난 자격 증명 복사 이력을 지운다.
 */
@Component
class CredentialCopyHistoryCleanupScheduler(
    private val credentialCopyHistoryRepository: CredentialCopyHistoryRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 5 * * *")
    @Transactional
    fun deleteOldHistories() {
        val deleted = credentialCopyHistoryRepository.deleteAllCopiedBefore(LocalDateTime.now(clock).minusDays(RETENTION_DAYS))
        if (deleted > 0) {
            log.info("Delete {} credential copy histories", deleted)
        }
    }

    companion object {
        private const val RETENTION_DAYS = 365L
    }
}
