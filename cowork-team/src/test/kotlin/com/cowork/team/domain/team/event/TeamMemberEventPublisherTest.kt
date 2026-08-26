package com.cowork.team.domain.team.event

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamRole.entity.TeamRole
import com.cowork.team.global.outbox.OutboxWriter
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class TeamMemberEventPublisherTest {
    private val entityManager = mockk<EntityManager>(relaxed = true)
    private val outboxWriter = mockk<OutboxWriter>()
    private val publisher = TeamMemberEventPublisher(entityManager, outboxWriter)

    @Test
    fun `publishUpsert는 teamId와 userId의 복합 key로 현재 멤버 상태를 발행`() {
        val eventSlot = slot<TeamMemberEvent>()
        every { outboxWriter.enqueue("team.member.event", "7:11", capture(eventSlot)) } just runs
        val team = Team(id = 7L, name = "Backend", description = null, iconUrl = null, ownerId = 11L)
        val member = TeamMember(team = team, userId = 11L, role = TeamRole.OWNER)

        publisher.publishUpsert(member)

        assertEquals("UPSERT", eventSlot.captured.eventType)
        assertEquals("OWNER", eventSlot.captured.role)
        assertEquals("Backend", eventSlot.captured.teamName)
        verify(exactly = 1) { outboxWriter.enqueue("team.member.event", "7:11", any()) }
        verifyOrder {
            entityManager.flush()
            outboxWriter.enqueue("team.member.event", "7:11", any())
        }
    }

    @Test
    fun `publishSnapshot은 발행 시각 대신 member와 team의 최신 source timestamp를 사용한다`() {
        val eventSlot = slot<TeamMemberEvent>()
        every { outboxWriter.enqueue("team.member.event", "7:11", capture(eventSlot)) } just runs
        val team = Team(id = 7L, name = "Backend", description = null, iconUrl = null, ownerId = 11L)
        val memberSourceTime = LocalDateTime.of(2026, 8, 26, 12, 0, 0, 123_456_789)
        val teamSourceTime = memberSourceTime.plusSeconds(1)
        team.createdAt = memberSourceTime.minusSeconds(1)
        team.updatedAt = teamSourceTime
        val member = TeamMember(team = team, userId = 11L, role = TeamRole.OWNER).apply {
            joinedAt = memberSourceTime.minusSeconds(2)
            updatedAt = memberSourceTime
        }

        publisher.publishSnapshot(member)

        assertEquals(true, eventSlot.captured.snapshot)
        assertEquals(
            teamSourceTime.atZone(ZoneId.systemDefault()).toInstant().truncatedTo(ChronoUnit.MICROS),
            eventSlot.captured.occurredAt,
        )
        verifyOrder {
            entityManager.flush()
            outboxWriter.enqueue("team.member.event", "7:11", match<TeamMemberEvent> { it.snapshot })
        }
    }
}
