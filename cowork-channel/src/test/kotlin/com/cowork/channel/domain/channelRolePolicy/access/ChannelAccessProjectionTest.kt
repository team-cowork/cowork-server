package com.cowork.channel.domain.channelRolePolicy.access

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ChannelAccessProjectionTest {
    private val version = Instant.parse("2026-11-25T00:00:00Z")

    @Test
    fun `현재 팀과 인가 version으로 계산된 판정만 유효하다`() {
        val current = access(setOf("message_read"))
        assertTrue(current.isCurrentFor(10L, version))
        assertFalse(current.isCurrentFor(10L, version.plusSeconds(1)))
        assertFalse(current.isCurrentFor(10L, null))
        assertFalse(current.isCurrentFor(11L, version))
    }

    private fun access(allowed: Set<String>) = ChannelAccessProjection(
        accessKey = "1:7",
        channelId = 1L,
        userId = 7L,
        teamId = 10L,
        allowedPermissions = allowed,
        canReadPrivate = false,
        sourceOccurredAt = version,
        sourceMembershipVersion = version,
        sourceAuthorizationVersion = version,
    )
}
