package com.cowork.voice

import com.cowork.voice.domain.VoiceSession
import com.cowork.voice.domain.VoiceSessionStatus
import com.cowork.voice.repository.VoiceParticipantRepository
import com.cowork.voice.repository.VoiceSessionRepository
import com.cowork.voice.service.VoiceSessionCleanupScheduler
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class VoiceSessionCleanupSchedulerTest {

    private val sessionRepository = mockk<VoiceSessionRepository>()
    private val participantRepository = mockk<VoiceParticipantRepository>()
    private val clock = Clock.fixed(Instant.parse("2026-01-24T12:00:00Z"), ZoneOffset.UTC)
    private val scheduler = VoiceSessionCleanupScheduler(sessionRepository, participantRepository, clock)

    private fun session(id: Long) = VoiceSession(channelId = 3, roomName = "room-$id", startedAt = LocalDateTime.of(2026, 1, 24, 9, 0), id = id)

    @Test
    fun `참여자가 없는 세션만 종료한다`() {
        val empty = session(1)
        val busy = session(2)
        every {
            sessionRepository.findAllByStatusAndStartedAtBefore(VoiceSessionStatus.ACTIVE, LocalDateTime.of(2026, 1, 24, 11, 50))
        } returns listOf(empty, busy)
        every { participantRepository.countBySessionIdAndLeftAtIsNull(1) } returns 0
        every { participantRepository.countBySessionIdAndLeftAtIsNull(2) } returns 3

        scheduler.endEmptySessions()

        assertFalse(empty.isActive)
        assertTrue(busy.isActive)
    }
}
