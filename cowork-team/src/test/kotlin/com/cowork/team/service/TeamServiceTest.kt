package com.cowork.team.service

import com.cowork.team.domain.Team
import com.cowork.team.domain.TeamMember
import com.cowork.team.domain.TeamRole
import com.cowork.team.dto.TeamEventPayload
import com.cowork.team.event.TeamEventPublisher
import com.cowork.team.repository.TeamMemberRepository
import com.cowork.team.repository.TeamRepository
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.Optional

class TeamServiceTest {

    private val teamRepository = mockk<TeamRepository>()
    private val teamMemberRepository = mockk<TeamMemberRepository>()
    private val teamEventPublisher = mockk<TeamEventPublisher>(relaxed = true)
    private val s3Service = mockk<S3Service>()

    private val service = TeamService(
        teamRepository = teamRepository,
        teamMemberRepository = teamMemberRepository,
        teamEventPublisher = teamEventPublisher,
        s3Service = s3Service,
    )

    @BeforeEach
    fun setUp() {
        TransactionSynchronizationManager.initSynchronization()
    }

    @AfterEach
    fun tearDown() {
        TransactionSynchronizationManager.clear()
    }

    private fun fireAfterCommit() {
        TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
    }

    @Test
    fun `deleteTeam은 OWNER 권한 통과 시 TEAM_DELETED 이벤트를 lifecycle 토픽으로 발행`() {
        val teamId = 10L
        val ownerId = 1L
        val team = Team(id = teamId, name = "팀A", description = null, iconUrl = null, ownerId = ownerId)

        every { teamMemberRepository.findByTeamIdAndUserIdAndRoleIn(teamId, ownerId, listOf(TeamRole.OWNER)) } returns
            TeamMember(team = team, userId = ownerId, role = TeamRole.OWNER)
        every { teamRepository.findById(teamId) } returns Optional.of(team)
        every { teamRepository.delete(team) } just Runs

        val captured = slot<TeamEventPayload>()
        every { teamEventPublisher.publishLifecycle(capture(captured)) } just Runs

        service.deleteTeam(ownerId, teamId)
        fireAfterCommit()

        verify(exactly = 1) { teamEventPublisher.publishLifecycle(any()) }
        assertEquals("TEAM_DELETED", captured.captured.eventType)
        assertEquals(teamId, captured.captured.teamId)
        assertEquals("팀A", captured.captured.teamName)
        assertEquals(ownerId, captured.captured.actorUserId)
    }
}
