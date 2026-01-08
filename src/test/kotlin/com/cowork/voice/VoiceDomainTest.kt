package com.cowork.voice

import com.cowork.voice.domain.VoiceSession
import com.cowork.voice.domain.VoiceSessionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class VoiceDomainTest {

    private val now = LocalDateTime.of(2026, 1, 4, 20, 0)

    @Test
    fun `세션을 끝내면 종료 시각을 기록한다`() {
        val session = VoiceSession(channelId = 3, roomName = "r1", startedAt = now.minusHours(1))

        session.end(now)

        assertEquals(VoiceSessionStatus.ENDED, session.status)
        assertEquals(now, session.endedAt)
        assertFalse(session.isActive)
    }

    @Test
    fun `이미 끝난 세션은 종료 시각을 바꾸지 않는다`() {
        val session = VoiceSession(channelId = 3, roomName = "r1", startedAt = now.minusHours(1))
        session.end(now)

        session.end(now.plusMinutes(5))

        assertEquals(now, session.endedAt)
    }
}
