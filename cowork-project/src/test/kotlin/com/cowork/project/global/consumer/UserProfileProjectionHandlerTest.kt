package com.cowork.project.global.consumer

import com.cowork.project.domain.user.entity.UserProfileProjection
import com.cowork.project.domain.user.repository.UserProfileProjectionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class UserProfileProjectionHandlerTest {

    private val repository = mockk<UserProfileProjectionRepository>(relaxed = true)
    private val profiles = mutableMapOf<Long, UserProfileProjection>()
    private val handler = UserProfileProjectionHandler(repository)

    @BeforeEach
    fun setUp() {
        profiles.clear()
        every { repository.findByIdForUpdate(any()) } answers { profiles[firstArg()] }
        every { repository.save(any()) } answers {
            firstArg<UserProfileProjection>().also { profiles[it.userId] = it }
        }
    }

    @Test
    fun `신규 사용자 프로필 이벤트는 projection을 새로 생성한다`() {
        val occurredAt = Instant.parse("2026-08-26T03:00:00.123456Z")

        handler.apply(userId = 7L, githubId = "github-user", deleted = false, occurredAt = occurredAt)

        assertEquals("github-user", profiles[7L]?.githubId)
        assertFalse(requireNotNull(profiles[7L]).deleted)
        verify(exactly = 1) { repository.save(any()) }
    }

    @Test
    fun `더 최신 버전 이벤트는 기존 projection을 갱신한다`() {
        val older = Instant.parse("2026-08-26T03:00:00Z")
        val newer = older.plusSeconds(10)
        handler.apply(userId = 7L, githubId = "old-id", deleted = false, occurredAt = older)

        handler.apply(userId = 7L, githubId = "new-id", deleted = false, occurredAt = newer)

        assertEquals("new-id", profiles[7L]?.githubId)
        assertEquals(newer, profiles[7L]?.sourceOccurredAt)
    }

    @Test
    fun `더 과거 버전 이벤트는 무시하고 저장하지 않는다`() {
        val newer = Instant.parse("2026-08-26T03:00:10Z")
        val older = Instant.parse("2026-08-26T03:00:00Z")
        handler.apply(userId = 7L, githubId = "new-id", deleted = false, occurredAt = newer)

        handler.apply(userId = 7L, githubId = "stale-id", deleted = false, occurredAt = older)

        assertEquals("new-id", profiles[7L]?.githubId)
        verify(exactly = 1) { repository.save(any()) }
    }

    @Test
    fun `삭제 이벤트는 deleted를 true로 반영한다`() {
        val occurredAt = Instant.parse("2026-08-26T03:00:00Z")
        handler.apply(userId = 7L, githubId = "github-user", deleted = false, occurredAt = occurredAt)

        handler.apply(userId = 7L, githubId = null, deleted = true, occurredAt = occurredAt.plusSeconds(1))

        assertTrue(requireNotNull(profiles[7L]).deleted)
    }
}
