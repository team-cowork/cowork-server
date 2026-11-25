package com.cowork.channel.domain.channelRolePolicy.access

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ChannelAccessProjectionTest {
    private val version = Instant.parse("2026-11-25T00:00:00Z")

    @Test
    fun `새 버전은 덮어쓰고 오래된 버전은 무시한다`() {
        val current = access(setOf("message_read"), occurredAt = version)
        assertTrue(current.merge(access(emptySet(), occurredAt = version.plusSeconds(1))))
        assertEquals(emptySet<String>(), current.allowedPermissions)
        assertFalse(current.merge(access(setOf("message_read"), occurredAt = version)))
        assertEquals(emptySet<String>(), current.allowedPermissions)
    }

    @Test
    fun `같은 버전은 두 기록이 모두 허용한 권한만 남긴다`() {
        val current = access(setOf("message_read", "message_send"), readPrivate = true)
        assertTrue(current.merge(access(setOf("message_read", "file_attach"), readPrivate = false)))
        assertEquals(setOf("message_read"), current.allowedPermissions)
        assertFalse(current.canReadPrivate)
        assertFalse(current.merge(access(setOf("message_read", "file_attach"))))
    }

    @Test
    fun `같은 버전이라도 인가 version이 다르면 권한을 되살리지 않고 모두 거부한다`() {
        val current = access(setOf("message_read"))
        assertTrue(current.merge(access(setOf("message_read"), authorizationVersion = version.minusSeconds(1))))
        assertEquals(emptySet<String>(), current.allowedPermissions)
    }

    @Test
    fun `현재 팀과 인가 version으로 계산된 판정만 유효하다`() {
        val current = access(setOf("message_read"))
        assertTrue(current.isCurrentFor(10L, version))
        assertFalse(current.isCurrentFor(10L, version.plusSeconds(1)))
        assertFalse(current.isCurrentFor(10L, null))
        assertFalse(current.isCurrentFor(11L, version))
    }

    @Test
    fun `허용 권한은 정렬된 쉼표 목록으로 저장한다`() {
        val converter = PermissionSetConverter()
        assertEquals(
            "message_read,voice_connect",
            converter.convertToDatabaseColumn(setOf("voice_connect", "message_read")),
        )
        assertEquals(
            setOf("message_read", "voice_connect"),
            converter.convertToEntityAttribute("message_read,voice_connect"),
        )
        assertEquals(emptySet<String>(), converter.convertToEntityAttribute(""))
    }

    private fun access(
        allowed: Set<String>,
        readPrivate: Boolean = false,
        occurredAt: Instant = version,
        authorizationVersion: Instant? = version,
    ) = ChannelAccessProjection(
        accessKey = "1:7",
        channelId = 1L,
        userId = 7L,
        teamId = 10L,
        allowedPermissions = allowed,
        canReadPrivate = readPrivate,
        sourceOccurredAt = occurredAt,
        sourceMembershipVersion = version,
        sourceAuthorizationVersion = authorizationVersion,
    )
}
