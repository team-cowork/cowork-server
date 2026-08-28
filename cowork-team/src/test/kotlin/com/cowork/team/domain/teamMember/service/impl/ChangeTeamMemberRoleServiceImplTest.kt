package com.cowork.team.domain.teamMember.service.impl

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.event.TeamMemberEventPublisher
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.presentation.data.request.ChangeRoleRequest
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamMember.service.TeamMemberAccessGuard
import com.cowork.team.domain.teamRole.entity.TeamRole
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class ChangeTeamMemberRoleServiceImplTest {

    private val teamRepository = mockk<TeamRepository>()
    private val teamMemberRepository = mockk<TeamMemberRepository>()
    private val teamMemberEventPublisher = mockk<TeamMemberEventPublisher>(relaxed = true)
    private val teamMemberAccessGuard = TeamMemberAccessGuard(teamRepository, teamMemberRepository)

    private val service =
        ChangeTeamMemberRoleServiceImpl(
            teamMemberRepository,
            teamMemberEventPublisher,
            teamMemberAccessGuard,
        )

    @Test
    fun `changeRole은 OWNER 통과 시 잠긴 member의 full state를 발행`() {
        val teamId = 5L
        val actorId = 1L
        val targetUserId = 7L
        val team = Team(id = teamId, name = "팀X", description = null, iconUrl = null, ownerId = actorId)

        every { teamMemberRepository.findByTeamIdAndUserIdAndRoleIn(teamId, actorId, listOf(TeamRole.OWNER)) } returns
            TeamMember(team = team, userId = actorId, role = TeamRole.OWNER)
        every { teamRepository.findByIdForUpdate(teamId) } returns team
        val targetMember = TeamMember(team = team, userId = targetUserId, role = TeamRole.MEMBER)
        every { teamMemberRepository.findByTeamIdAndUserIdForUpdate(teamId, targetUserId) } returns targetMember

        service.execute(actorId, teamId, targetUserId, ChangeRoleRequest(role = TeamRole.ADMIN))

        verify(exactly = 1) { teamMemberEventPublisher.publishUpsert(targetMember, any()) }
        assertEquals(TeamRole.ADMIN, targetMember.role)
    }

    @Test
    fun `changeRole은 OWNER로 변경 시도 시 BAD_REQUEST`() {
        val teamId = 5L
        val actorId = 1L
        val team = Team(id = teamId, name = "팀X", description = null, iconUrl = null, ownerId = actorId)

        every { teamRepository.findByIdForUpdate(teamId) } returns team
        every { teamMemberRepository.findByTeamIdAndUserIdAndRoleIn(teamId, actorId, listOf(TeamRole.OWNER)) } returns
            TeamMember(team = team, userId = actorId, role = TeamRole.OWNER)

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(actorId, teamId, 7L, ChangeRoleRequest(role = TeamRole.OWNER))
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
        verify(exactly = 0) { teamMemberEventPublisher.publishUpsert(any(), any()) }
    }
}
