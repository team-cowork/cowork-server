package com.cowork.channel.domain.channel.entity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime

class ChannelArchiveTest {

    private fun channel() = Channel(
        id = 1L,
        teamId = 10L,
        name = "general",
        type = ChannelType.TEXT,
        viewType = ChannelViewType.TEXT,
        description = null,
        createdBy = 1L,
    )

    @Test
    fun `보관하면 보관 시각과 보관한 사용자를 기록하고 복원하면 지운다`() {
        val channel = channel()
        val archivedAt = LocalDateTime.of(2026, 11, 10, 9, 0)

        channel.archive(7L, archivedAt)

        assertTrue(channel.isArchived)
        assertEquals(archivedAt, channel.archivedAt)
        assertEquals(7L, channel.archivedBy)

        channel.unarchive()

        assertFalse(channel.isArchived)
        assertNull(channel.archivedAt)
        assertNull(channel.archivedBy)
    }

    @Test
    fun `이미 보관된 채널은 다시 보관할 수 없고 활성 채널은 복원할 수 없다`() {
        val channel = channel()

        assertThrows(IllegalStateException::class.java) { channel.unarchive() }
        channel.archive(7L, LocalDateTime.of(2026, 11, 10, 9, 0))
        assertThrows(IllegalStateException::class.java) { channel.archive(7L, LocalDateTime.of(2026, 11, 10, 9, 1)) }
    }

    @Test
    fun `이벤트 상태는 채널의 보관 상태를 따라간다`() {
        val channel = channel()
        val state = ChannelEventState.create(
            channel,
            deleted = false,
            requestedAt = Instant.parse("2026-11-10T00:00:00Z"),
        )
        assertFalse(state.archived)

        channel.archive(7L, LocalDateTime.of(2026, 11, 10, 9, 0))
        state.apply(channel, deleted = false, requestedAt = Instant.parse("2026-11-10T00:00:01Z"))
        assertTrue(state.archived)

        channel.unarchive()
        state.apply(channel, deleted = false, requestedAt = Instant.parse("2026-11-10T00:00:02Z"))
        assertFalse(state.archived)
    }
}
