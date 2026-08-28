package com.cowork.team.domain.team.event

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.entity.TeamMemberEventState
import com.cowork.team.domain.teamMember.repository.TeamMemberEventStateRepository
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
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
import java.time.Instant

class TeamMemberEventPublisherTest {
    private val teamRepository = mockk<TeamRepository>()
    private val memberRepository = mockk<TeamMemberRepository>()
    private val stateRepository = mockk<TeamMemberEventStateRepository>()
    private val entityManager = mockk<EntityManager>(relaxed = true)
    private val outboxWriter = mockk<OutboxWriter>()
    private val publisher = TeamMemberEventPublisher(
        teamRepository,
        memberRepository,
        stateRepository,
        entityManager,
        outboxWriter,
    )

    @Test
    fun `upsert는 aggregate와 composite ledger를 잠그고 full state를 적재한다`() {
        val requestedAt = Instant.parse("2026-08-26T03:00:00.123456789Z")
        val team = Team(id = 7L, name = "Backend", description = null, iconUrl = null, ownerId = 11L)
        val member = TeamMember(team = team, userId = 11L, role = TeamRole.OWNER)
        val stateSlot = slot<TeamMemberEventState>()
        val eventSlot = slot<TeamMemberEvent>()
        every { teamRepository.findByIdForUpdate(7L) } returns team
        every { memberRepository.findByTeamIdAndUserIdForUpdate(7L, 11L) } returns member
        every { stateRepository.findByKeyForUpdate(7L, 11L) } returns null
        every { stateRepository.save(capture(stateSlot)) } answers { firstArg() }
        every { outboxWriter.enqueue("team.member.event", "7:11", capture(eventSlot)) } just runs

        publisher.publishUpsert(member, requestedAt)

        assertEquals("UPSERT", eventSlot.captured.eventType)
        assertEquals("OWNER", eventSlot.captured.role)
        assertEquals("Backend", eventSlot.captured.teamName)
        assertEquals(Instant.parse("2026-08-26T03:00:00.123456Z"), eventSlot.captured.occurredAt)
        verifyOrder {
            teamRepository.findByIdForUpdate(7L)
            memberRepository.findByTeamIdAndUserIdForUpdate(7L, 11L)
            stateRepository.findByKeyForUpdate(7L, 11L)
            stateRepository.save(any())
            entityManager.flush()
            outboxWriter.enqueue("team.member.event", "7:11", any())
        }
    }

    @Test
    fun `delete는 clock rollback에도 이전 상태보다 최신 tombstone을 만든다`() {
        val previousVersion = Instant.parse("2026-08-26T03:00:10.123456Z")
        val team = Team(id = 7L, name = "Backend", description = null, iconUrl = null, ownerId = 11L)
        val member = TeamMember(team = team, userId = 12L, role = TeamRole.MEMBER)
        val state = TeamMemberEventState.create(team, member, deleted = false, requestedAt = previousVersion)
        val eventSlot = slot<TeamMemberEvent>()
        every { teamRepository.findByIdForUpdate(7L) } returns team
        every { memberRepository.findByTeamIdAndUserIdForUpdate(7L, 12L) } returns member
        every { stateRepository.findByKeyForUpdate(7L, 12L) } returns state
        every { stateRepository.save(state) } returns state
        every { outboxWriter.enqueue("team.member.event", "7:12", capture(eventSlot)) } just runs

        publisher.publishDelete(member, previousVersion.minusSeconds(5))

        assertEquals(true, state.deleted)
        assertEquals(previousVersion.plusNanos(1_000), state.stateOccurredAt)
        assertEquals("DELETE", eventSlot.captured.eventType)
        assertEquals(previousVersion.plusNanos(1_000), eventSlot.captured.occurredAt)
    }

    @Test
    fun `재가입 upsert는 clock rollback 중에도 기존 tombstone보다 최신 상태가 된다`() {
        val tombstoneVersion = Instant.parse("2026-08-26T03:00:10.123456Z")
        val team = Team(id = 7L, name = "Backend", description = null, iconUrl = null, ownerId = 11L)
        val member = TeamMember(team = team, userId = 12L, role = TeamRole.ADMIN)
        val state = TeamMemberEventState.create(team, member, deleted = true, requestedAt = tombstoneVersion)
        val eventSlot = slot<TeamMemberEvent>()
        every { teamRepository.findByIdForUpdate(7L) } returns team
        every { memberRepository.findByTeamIdAndUserIdForUpdate(7L, 12L) } returns member
        every { stateRepository.findByKeyForUpdate(7L, 12L) } returns state
        every { stateRepository.save(state) } returns state
        every { outboxWriter.enqueue("team.member.event", "7:12", capture(eventSlot)) } just runs

        publisher.publishUpsert(member, tombstoneVersion.minusSeconds(5))

        assertEquals(false, state.deleted)
        assertEquals(tombstoneVersion.plusNanos(1_000), state.stateOccurredAt)
        assertEquals("UPSERT", eventSlot.captured.eventType)
        assertEquals("ADMIN", eventSlot.captured.role)
    }

    @Test
    fun `snapshot은 hard delete 뒤에도 ledger tombstone과 persisted version을 재발행한다`() {
        val version = Instant.parse("2026-08-26T03:00:10.123456Z")
        val team = Team(id = 7L, name = "Backend", description = null, iconUrl = null, ownerId = 11L)
        val member = TeamMember(team = team, userId = 12L, role = TeamRole.MEMBER)
        val state = TeamMemberEventState.create(team, member, deleted = true, requestedAt = version)
        val eventSlot = slot<TeamMemberEvent>()
        every { outboxWriter.enqueue("team.member.event", "7:12", capture(eventSlot)) } just runs

        publisher.publishSnapshot(state)

        assertEquals("DELETE", eventSlot.captured.eventType)
        assertEquals(version, eventSlot.captured.occurredAt)
        assertEquals(true, eventSlot.captured.snapshot)
        verify(exactly = 0) { teamRepository.findByIdForUpdate(any()) }
        verify(exactly = 0) { memberRepository.findByTeamIdAndUserIdForUpdate(any(), any()) }
        verify(exactly = 0) { stateRepository.save(any()) }
    }
}
