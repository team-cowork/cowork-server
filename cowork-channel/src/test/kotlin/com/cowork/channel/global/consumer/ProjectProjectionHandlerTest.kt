package com.cowork.channel.global.consumer

import com.cowork.channel.domain.project.entity.ProjectProjection
import com.cowork.channel.domain.project.repository.ProjectProjectionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional

class ProjectProjectionHandlerTest {

    private val repository = mockk<ProjectProjectionRepository>(relaxed = true)
    private val handler = ProjectProjectionHandler(repository)

    @Test
    fun `updated event upserts project team mapping`() {
        every { repository.findById(7L) } returns Optional.empty()
        every { repository.save(any()) } answers { firstArg() }

        val occurredAt = Instant.parse("2026-08-26T03:00:00Z")
        handler.apply(ProjectEventPayload(eventType = "UPDATED", projectId = 7L, teamId = 3L, occurredAt = occurredAt))

        verify(exactly = 1) {
            repository.save(
                match<ProjectProjection> {
                    it.projectId == 7L && it.teamId == 3L && !it.deleted && it.sourceOccurredAt == occurredAt
                },
            )
        }
    }

    @Test
    fun `deleted event keeps a versioned tombstone`() {
        every { repository.findById(7L) } returns Optional.empty()
        every { repository.save(any()) } answers { firstArg() }
        val occurredAt = Instant.parse("2026-08-26T03:00:00Z")

        handler.apply(ProjectEventPayload(eventType = "DELETED", projectId = 7L, teamId = 3L, occurredAt = occurredAt))

        verify(exactly = 1) {
            repository.save(match<ProjectProjection> { it.deleted && it.sourceOccurredAt == occurredAt })
        }
    }

    @Test
    fun `같은 DB microsecond의 update는 project delete tombstone을 되살리지 않는다`() {
        val deletedAt = Instant.parse("2026-08-26T03:00:00.123456Z")
        val projection = ProjectProjection(7L, 3L, deleted = true, sourceOccurredAt = deletedAt)
        every { repository.findById(7L) } returns Optional.of(projection)

        handler.apply(
            ProjectEventPayload(
                eventType = "UPDATED",
                projectId = 7L,
                teamId = 4L,
                occurredAt = Instant.parse("2026-08-26T03:00:00.123456999Z"),
            ),
        )

        verify(exactly = 0) { repository.save(any()) }
        assertEquals(true, projection.deleted)
        assertEquals(deletedAt, projection.sourceOccurredAt)
    }

    @Test
    fun `새 projection version은 DB microsecond 정밀도로 저장한다`() {
        every { repository.findById(7L) } returns Optional.empty()
        val saved = mutableListOf<ProjectProjection>()
        every { repository.save(capture(saved)) } answers { firstArg() }

        handler.apply(
            ProjectEventPayload(
                eventType = "UPDATED",
                projectId = 7L,
                teamId = 3L,
                occurredAt = Instant.parse("2026-08-26T03:00:00.123456999Z"),
            ),
        )

        assertEquals(Instant.parse("2026-08-26T03:00:00.123456Z"), saved.single().sourceOccurredAt)
    }
}
