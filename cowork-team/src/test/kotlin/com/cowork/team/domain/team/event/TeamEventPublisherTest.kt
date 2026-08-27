package com.cowork.team.domain.team.event

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.entity.TeamEventState
import com.cowork.team.domain.team.repository.TeamEventStateRepository
import com.cowork.team.domain.team.repository.TeamRepository
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

class TeamEventPublisherTest {

    private val teamRepository = mockk<TeamRepository>()
    private val stateRepository = mockk<TeamEventStateRepository>()
    private val entityManager = mockk<EntityManager>(relaxed = true)
    private val outboxWriter = mockk<OutboxWriter>()
    private val publisher = TeamEventPublisher(teamRepository, stateRepository, entityManager, outboxWriter)

    @Test
    fun `notification trigger는 action stream에만 적재한다`() {
        val event = NotificationTriggerEvent(
            type = "MEMBER_INVITED",
            targetUserIds = listOf(11L, 12L),
            forcedUserIds = listOf(11L),
            data = mapOf("teamId" to 7L),
        )
        every { outboxWriter.enqueue("notification.trigger", "7", event) } just runs

        publisher.publishNotification(7L, event)

        verifyOrder {
            entityManager.flush()
            outboxWriter.enqueue("notification.trigger", "7", event)
        }
        verify(exactly = 0) { stateRepository.save(any()) }
    }

    @Test
    fun `created 상태는 aggregate와 ledger를 잠그고 full state를 outbox에 적재한다`() {
        val requestedAt = Instant.parse("2026-08-26T03:00:00.123456789Z")
        val team = Team(
            id = 7L,
            name = "Backend",
            description = "server",
            iconUrl = "https://cdn/icon.png",
            ownerId = 11L,
            githubInstallationId = 42L,
            githubOrgLogin = "cowork",
        )
        val stateSlot = slot<TeamEventState>()
        val eventSlot = slot<TeamEventPayload>()
        every { teamRepository.findByIdForUpdate(7L) } returns team
        every { stateRepository.findByTeamIdForUpdate(7L) } returns null
        every { stateRepository.save(capture(stateSlot)) } answers { firstArg() }
        every { outboxWriter.enqueue("team.lifecycle", "7", capture(eventSlot)) } just runs

        publisher.publishCreated(team, 11L, requestedAt)

        val expectedVersion = Instant.parse("2026-08-26T03:00:00.123456Z")
        assertEquals(expectedVersion, stateSlot.captured.stateOccurredAt)
        assertEquals("TEAM_CREATED", eventSlot.captured.eventType)
        assertEquals(expectedVersion, eventSlot.captured.occurredAt)
        assertEquals("server", eventSlot.captured.description)
        assertEquals(42L, eventSlot.captured.githubInstallationId)
        verifyOrder {
            teamRepository.findByIdForUpdate(7L)
            stateRepository.findByTeamIdForUpdate(7L)
            stateRepository.save(any())
            entityManager.flush()
            outboxWriter.enqueue("team.lifecycle", "7", any())
        }
    }

    @Test
    fun `clock rollback에서도 다음 상태 version은 이전보다 1 microsecond 증가한다`() {
        val previousVersion = Instant.parse("2026-08-26T03:00:10.123456Z")
        val team = Team(id = 7L, name = "Renamed", description = null, iconUrl = null, ownerId = 11L)
        val state = TeamEventState.create(team, 11L, deleted = false, requestedAt = previousVersion)
        val eventSlot = slot<TeamEventPayload>()
        every { teamRepository.findByIdForUpdate(7L) } returns team
        every { stateRepository.findByTeamIdForUpdate(7L) } returns state
        every { stateRepository.save(state) } returns state
        every { outboxWriter.enqueue("team.lifecycle", "7", capture(eventSlot)) } just runs

        publisher.publishUpdated(team, 12L, previousVersion.minusSeconds(5))

        assertEquals(previousVersion.plusNanos(1_000), state.stateOccurredAt)
        assertEquals(previousVersion.plusNanos(1_000), eventSlot.captured.occurredAt)
        assertEquals(12L, state.actorUserId)
        assertEquals("Renamed", eventSlot.captured.teamName)
    }

    @Test
    fun `snapshot은 삭제 ledger의 tombstone과 persisted version을 그대로 재발행한다`() {
        val version = Instant.parse("2026-08-26T03:00:10.123456Z")
        val team = Team(id = 7L, name = "Backend", description = null, iconUrl = null, ownerId = 11L)
        val state = TeamEventState.create(team, 11L, deleted = true, requestedAt = version)
        val eventSlot = slot<TeamEventPayload>()
        every { outboxWriter.enqueue("team.lifecycle", "7", capture(eventSlot)) } just runs

        publisher.publishSnapshot(state)

        assertEquals("TEAM_DELETED", eventSlot.captured.eventType)
        assertEquals(version, eventSlot.captured.occurredAt)
        assertEquals(true, eventSlot.captured.snapshot)
        verify(exactly = 0) { teamRepository.findByIdForUpdate(any()) }
        verify(exactly = 0) { stateRepository.save(any()) }
    }
}
