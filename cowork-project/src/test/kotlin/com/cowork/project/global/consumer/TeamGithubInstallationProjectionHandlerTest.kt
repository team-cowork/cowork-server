package com.cowork.project.global.consumer

import com.cowork.project.domain.github.entity.TeamGithubInstallation
import com.cowork.project.domain.github.entity.TeamGithubInstallationEventState
import com.cowork.project.domain.github.entity.TeamGithubInstallationOwnershipFence
import com.cowork.project.domain.github.repository.TeamGithubInstallationEventStateRepository
import com.cowork.project.domain.github.repository.TeamGithubInstallationOwnershipFenceRepository
import com.cowork.project.domain.github.repository.TeamGithubInstallationRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class TeamGithubInstallationProjectionHandlerTest {

    private val installationRepository = mockk<TeamGithubInstallationRepository>(relaxed = true)
    private val stateRepository = mockk<TeamGithubInstallationEventStateRepository>(relaxed = true)
    private val ownershipFenceRepository = mockk<TeamGithubInstallationOwnershipFenceRepository>(relaxed = true)
    private val installations = mutableMapOf<Long, TeamGithubInstallation>()
    private val states = mutableMapOf<Long, TeamGithubInstallationEventState>()
    private val fences = mutableMapOf<Long, TeamGithubInstallationOwnershipFence>()
    private val handler = TeamGithubInstallationProjectionHandler(
        installationRepository,
        stateRepository,
        ownershipFenceRepository,
    )

    @BeforeEach
    fun setUp() {
        installations.clear()
        states.clear()
        fences.clear()
        every { installationRepository.findByTeamIdForUpdate(any()) } answers {
            installations[firstArg()]
        }
        every { installationRepository.findByInstallationIdForUpdate(any()) } answers {
            val installationId = firstArg<Long>()
            installations.values.firstOrNull { it.installationId == installationId }
        }
        every { installationRepository.save(any()) } answers {
            firstArg<TeamGithubInstallation>().also { installations[it.teamId] = it }
        }
        every { installationRepository.delete(any()) } answers {
            installations.remove(firstArg<TeamGithubInstallation>().teamId).let { }
        }
        every { stateRepository.findByTeamIdForUpdate(any()) } answers { states[firstArg()] }
        every { stateRepository.save(any()) } answers {
            firstArg<TeamGithubInstallationEventState>().also { states[it.teamId] = it }
        }
        every { ownershipFenceRepository.findByInstallationIdForUpdate(any()) } answers {
            fences[firstArg()]
        }
        every { ownershipFenceRepository.save(any()) } answers {
            firstArg<TeamGithubInstallationOwnershipFence>().also { fences[it.installationId] = it }
        }
    }

    @Test
    fun `team lifecycle UPSERT는 active projection과 team ledger 및 ownership fence를 함께 저장한다`() {
        val occurredAt = Instant.parse("2026-08-26T03:00:00.123456Z")

        handler.apply(100L, 1L, "my-org", teamDeleted = false, occurredAt)

        assertEquals(1L, installations[100L]?.installationId)
        assertEquals("my-org", installations[100L]?.orgLogin)
        assertFalse(requireNotNull(states[100L]).deleted)
        assertEquals(occurredAt, states[100L]?.sourceOccurredAt)
        assertEquals(100L, fences[1L]?.ownerTeamId)
        assertTrue(requireNotNull(fences[1L]).active)
    }

    @Test
    fun `disconnect는 active row를 지워도 durable tombstone과 inactive ownership fence를 남긴다`() {
        val connectedAt = Instant.parse("2026-08-26T03:00:00Z")
        val disconnectedAt = connectedAt.plusSeconds(10)
        handler.apply(100L, 1L, "my-org", teamDeleted = false, connectedAt)

        handler.apply(100L, null, null, teamDeleted = false, disconnectedAt)
        handler.apply(100L, 1L, "my-org", teamDeleted = false, connectedAt)

        assertNull(installations[100L])
        assertTrue(requireNotNull(states[100L]).deleted)
        assertEquals(1L, states[100L]?.installationId)
        assertEquals(disconnectedAt, states[100L]?.sourceOccurredAt)
        assertFalse(requireNotNull(fences[1L]).active)
        assertEquals(disconnectedAt, fences[1L]?.sourceOccurredAt)
    }

    @Test
    fun `더 최신인 다른 팀 claim은 이전 active row를 해제하고 이전 팀 ledger도 같은 시각으로 fence한다`() {
        val oldClaimAt = Instant.parse("2026-08-26T03:00:00Z")
        val newClaimAt = oldClaimAt.plusSeconds(10)
        handler.apply(100L, 1L, "old-org", teamDeleted = false, oldClaimAt)

        handler.apply(200L, 1L, "new-org", teamDeleted = false, newClaimAt)

        assertNull(installations[100L])
        assertEquals(1L, installations[200L]?.installationId)
        assertTrue(requireNotNull(states[100L]).deleted)
        assertEquals(newClaimAt, states[100L]?.sourceOccurredAt)
        assertFalse(requireNotNull(states[200L]).deleted)
        assertEquals(200L, fences[1L]?.ownerTeamId)
        assertTrue(requireNotNull(fences[1L]).active)
        assertEquals(newClaimAt, fences[1L]?.sourceOccurredAt)
    }

    @Test
    fun `서로 다른 팀의 같은 시각 claim은 임의 owner를 선택하지 않고 fail closed한다`() {
        val occurredAt = Instant.parse("2026-08-26T03:00:00Z")
        handler.apply(100L, 1L, "first-org", teamDeleted = false, occurredAt)

        assertThrows(IllegalStateException::class.java) {
            handler.apply(200L, 1L, "second-org", teamDeleted = false, occurredAt)
        }

        assertEquals(100L, fences[1L]?.ownerTeamId)
        assertNull(installations[200L])
    }
}
