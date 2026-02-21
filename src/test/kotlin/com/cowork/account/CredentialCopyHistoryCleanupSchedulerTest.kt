package com.cowork.account

import com.cowork.channel.account.repository.CredentialCopyHistoryRepository
import com.cowork.channel.account.service.CredentialCopyHistoryCleanupScheduler
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class CredentialCopyHistoryCleanupSchedulerTest {

    private val repository = mockk<CredentialCopyHistoryRepository>()
    private val scheduler = CredentialCopyHistoryCleanupScheduler(
        repository, Clock.fixed(Instant.parse("2026-02-01T05:00:00Z"), ZoneOffset.UTC),
    )

    @Test
    fun `1년이 지난 복사 이력을 지운다`() {
        every { repository.deleteAllCopiedBefore(any()) } returns 0

        scheduler.deleteOldHistories()

        verify { repository.deleteAllCopiedBefore(LocalDateTime.of(2025, 2, 1, 5, 0)) }
    }
}
