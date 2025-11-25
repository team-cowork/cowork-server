package com.cowork.preference

import com.cowork.preference.domain.ResourceSetting
import com.cowork.preference.domain.ResourceType
import com.cowork.preference.event.AccountStatusChangedEvent
import com.cowork.preference.repository.ResourceSettingRepository
import com.cowork.preference.service.AccountStatusExpirationScheduler
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AccountStatusExpirationSchedulerTest {

    private val repository = mockk<ResourceSettingRepository>()
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val clock = Clock.fixed(Instant.parse("2025-11-14T12:00:00Z"), ZoneOffset.UTC)
    private val scheduler = AccountStatusExpirationScheduler(repository, eventPublisher, clock)

    private fun setting(userId: Long, status: String, expiresAt: String?) = ResourceSetting(
        ResourceType.ACCOUNT, userId,
        mutableMapOf<String, Any?>("status" to status).apply { expiresAt?.let { put("status_expires_at", it) } },
    )

    @Test
    fun `만료 시각이 지난 상태는 온라인으로 되돌린다`() {
        val expired = setting(1, "DO_NOT_DISTURB", "2025-11-14T11:59:59Z")
        every { repository.findAccountsWithStatusExpiry() } returns listOf(expired)

        scheduler.expireStatuses()

        assertEquals("ONLINE", expired.settings["status"])
        assertFalse(expired.settings.containsKey("status_expires_at"))
        verify { eventPublisher.publishEvent(AccountStatusChangedEvent(1, "DO_NOT_DISTURB", "ONLINE", "EXPIRED")) }
    }

    @Test
    fun `아직 만료되지 않은 상태는 그대로 둔다`() {
        val active = setting(1, "DO_NOT_DISTURB", "2025-11-14T13:00:00Z")
        every { repository.findAccountsWithStatusExpiry() } returns listOf(active)

        scheduler.expireStatuses()

        assertEquals("DO_NOT_DISTURB", active.settings["status"])
        verify(exactly = 0) { eventPublisher.publishEvent(any<Any>()) }
    }

    @Test
    fun `만료 시각을 읽을 수 없으면 바로 만료한다`() {
        val broken = setting(1, "DO_NOT_DISTURB", "tomorrow")
        every { repository.findAccountsWithStatusExpiry() } returns listOf(broken)

        scheduler.expireStatuses()

        assertEquals("ONLINE", broken.settings["status"])
    }

    @Test
    fun `이미 온라인이면 만료 시각만 지우고 이벤트는 발행하지 않는다`() {
        val online = setting(1, "ONLINE", "2025-11-14T11:00:00Z")
        every { repository.findAccountsWithStatusExpiry() } returns listOf(online)

        scheduler.expireStatuses()

        assertFalse(online.settings.containsKey("status_expires_at"))
        verify(exactly = 0) { eventPublisher.publishEvent(any<Any>()) }
    }
}
