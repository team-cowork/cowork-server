package com.cowork.team.service

import com.cowork.team.domain.Team
import com.cowork.team.domain.TeamMember
import com.cowork.team.domain.TeamRole
import com.cowork.team.dto.ChangeRoleRequest
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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import team.themoment.sdk.exception.ExpectedException
import java.util.Optional

class TeamMemberServiceTest {

    private val teamRepository = mockk<TeamRepository>()
    private val teamMemberRepository = mockk<TeamMemberRepository>()
    private val teamEventPublisher = mockk<TeamEventPublisher>(relaxed = true)

    private val service = TeamMemberService(
        teamRepository = teamRepository,
        teamMemberRepository = teamMemberRepository,
        teamEventPublisher = teamEventPublisher,
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
    fun `changeRole은 OWNER 통과 시 ROLE_CHANGED 이벤트를 newRole 포함하여 lifecycle 토픽으로 발행`() {
        val teamId = 5L
        val actorId = 1L
        val targetUserId = 7L
        val team = Team(id = teamId, name = "팀X", description = null, iconUrl = null, ownerId = actorId)

        every { teamMemberRepository.findByTeamIdAndUserIdAndRoleIn(teamId, actorId, listOf(TeamRole.OWNER)) } returns
            TeamMember(team = team, userId = actorId, role = TeamRole.OWNER)
        every { teamRepository.findById(teamId) } returns Optional.of(team)
        every { teamMemberRepository.findByTeamIdAndUserId(teamId, targetUserId) } returns
            TeamMember(team = team, userId = targetUserId, role = TeamRole.MEMBER)

        val captured = slot<TeamEventPayload>()
        every { teamEventPublisher.publishLifecycle(capture(captured)) } just Runs

        service.changeRole(actorId, teamId, targetUserId, ChangeRoleRequest(role = TeamRole.ADMIN))
        fireAfterCommit()

        verify(exactly = 1) { teamEventPublisher.publishLifecycle(any()) }
        assertEquals("ROLE_CHANGED", captured.captured.eventType)
        assertEquals(listOf(targetUserId), captured.captured.targetUserIds)
        assertEquals("ADMIN", captured.captured.newRole)
    }

    @Test
    fun `changeRole은 OWNER로 변경 시도 시 BAD_REQUEST`() {
        val teamId = 5L
        val actorId = 1L
        val team = Team(id = teamId, name = "팀X", description = null, iconUrl = null, ownerId = actorId)

        every { teamMemberRepository.findByTeamIdAndUserIdAndRoleIn(teamId, actorId, listOf(TeamRole.OWNER)) } returns
            TeamMember(team = team, userId = actorId, role = TeamRole.OWNER)

        val ex = assertThrows(ExpectedException::class.java) {
            service.changeRole(actorId, teamId, 7L, ChangeRoleRequest(role = TeamRole.OWNER))
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
        verify(exactly = 0) { teamEventPublisher.publishLifecycle(any()) }
    }

}
