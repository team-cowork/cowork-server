package com.cowork.preference.service

import com.cowork.preference.domain.SettingSchema
import com.cowork.preference.event.AccountStatusChangedEvent
import com.cowork.preference.repository.ResourceSettingRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Component
class AccountStatusExpirationScheduler(
    private val resourceSettingRepository: ResourceSettingRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${cowork.preference.status-expiry-interval:PT1M}")
    @Transactional
    fun expireStatuses() {
        val now = Instant.now(clock)
        val expired = resourceSettingRepository.findAccountsWithStatusExpiry().filter { setting ->
            val expiresAt = (setting.settings[SettingSchema.STATUS_EXPIRES_AT] as? String)?.let(SettingSchema::parseInstant)
            expiresAt == null || !expiresAt.isAfter(now)
        }
        expired.forEach { setting ->
            val previous = setting.settings[SettingSchema.STATUS] as? String
            setting.merge(mapOf(SettingSchema.STATUS to SettingSchema.STATUS_ONLINE, SettingSchema.STATUS_EXPIRES_AT to null))
            if (previous != SettingSchema.STATUS_ONLINE) {
                eventPublisher.publishEvent(
                    AccountStatusChangedEvent(setting.resourceId, previous, SettingSchema.STATUS_ONLINE, "EXPIRED"),
                )
            }
        }
        if (expired.isNotEmpty()) {
            log.info("Reset {} expired account statuses", expired.size)
        }
    }
}
