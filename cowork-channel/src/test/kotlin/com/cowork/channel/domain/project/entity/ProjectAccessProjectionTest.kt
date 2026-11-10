package com.cowork.channel.domain.project.entity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ProjectAccessProjectionTest {

    private val membershipVersion = Instant.parse("2026-11-01T00:00:00Z")
    private val authorizationVersion = Instant.parse("2026-11-01T00:00:00Z")

    private fun access(
        occurredAt: Instant = Instant.parse("2026-11-10T00:00:00Z"),
        canRead: Boolean = true,
        canChannelArchive: Boolean = true,
        sourceAuthorizationVersion: Instant? = authorizationVersion,
    ) = ProjectAccessProjection(
        accessKey = "5:7",
        projectId = 5L,
        userId = 7L,
        canRead = canRead,
        canChannelArchive = canChannelArchive,
        sourceOccurredAt = occurredAt,
        sourceMembershipVersion = membershipVersion,
        sourceAuthorizationVersion = sourceAuthorizationVersion,
    )

    @Test
    fun `채널 보관은 읽기와 보관 권한이 있고 현재 권한 버전으로 계산된 값일 때만 허용한다`() {
        assertTrue(access().allowsChannelArchive(authorizationVersion))
        assertFalse(access(canRead = false).allowsChannelArchive(authorizationVersion))
        assertFalse(access(canChannelArchive = false).allowsChannelArchive(authorizationVersion))
        assertFalse(access().allowsChannelArchive(authorizationVersion.plusSeconds(1)))
        assertFalse(access().allowsChannelArchive(null))
    }

    @Test
    fun `더 새로운 기록은 권한을 그대로 덮어쓰고 오래된 기록은 무시한다`() {
        val existing = access(canChannelArchive = false)

        assertFalse(existing.merge(access(occurredAt = Instant.parse("2026-11-09T00:00:00Z"))))
        assertFalse(existing.canChannelArchive)

        assertTrue(existing.merge(access(occurredAt = Instant.parse("2026-11-11T00:00:00Z"))))
        assertTrue(existing.canChannelArchive)
        assertEquals(Instant.parse("2026-11-11T00:00:00Z"), existing.sourceOccurredAt)
    }

    @Test
    fun `같은 버전의 기록은 양쪽이 모두 허용할 때만 권한을 유지한다`() {
        val existing = access()

        assertTrue(existing.merge(access(canChannelArchive = false)))
        assertFalse(existing.canChannelArchive)

        assertFalse(existing.merge(access()))
        assertFalse(existing.canChannelArchive)
    }

    @Test
    fun `같은 버전이라도 권한 버전이 다르면 읽기 권한을 되살리지 않는다`() {
        val existing = access()

        assertTrue(existing.merge(access(sourceAuthorizationVersion = authorizationVersion.minusSeconds(1))))
        assertFalse(existing.canRead)
    }
}
