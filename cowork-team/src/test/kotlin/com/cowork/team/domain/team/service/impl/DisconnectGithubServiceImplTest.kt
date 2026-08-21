package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamRole.entity.TeamRole
import com.cowork.team.global.consumer.TeamGithubEventPublisher
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class DisconnectGithubServiceImplTest {

    private val teamRepository = mockk<TeamRepository>(relaxed = true)
    private val teamMemberRepository = mockk<TeamMemberRepository>()
    private val teamAccessGuard = TeamAccessGuard(teamRepository, teamMemberRepository)
    private val teamGithubEventPublisher = mockk<TeamGithubEventPublisher>(relaxed = true)

    private val service = DisconnectGithubServiceImpl(teamRepository, teamAccessGuard, teamGithubEventPublisher)

    @Test
    fun `execute는 연결된 installation을 해제하고 disconnected 이벤트를 발행한다`() {
        val team = Team(id = 1L, name = "테스트팀", description = null, iconUrl = null, ownerId = 10L)
        team.connectGithub(installationId = 42L, orgLogin = "my-org")
        val ownerMember = TeamMember(team = team, userId = 10L, role = TeamRole.OWNER)
        every {
            teamMemberRepository.findByTeamIdAndUserIdAndRoleIn(1L, 10L, listOf(TeamRole.OWNER, TeamRole.ADMIN))
        } returns ownerMember
        every { teamRepository.save(team) } returns team

        service.execute(10L, 1L)

        assertNull(team.githubInstallationId)
        assertNull(team.githubOrgLogin)
        verify(exactly = 1) { teamGithubEventPublisher.publishDisconnected(42L) }
    }

    @Test
    fun `execute는 연결된 적이 없으면 이벤트를 발행하지 않는다`() {
        val team = Team(id = 1L, name = "테스트팀", description = null, iconUrl = null, ownerId = 10L)
        val ownerMember = TeamMember(team = team, userId = 10L, role = TeamRole.OWNER)
        every {
            teamMemberRepository.findByTeamIdAndUserIdAndRoleIn(1L, 10L, listOf(TeamRole.OWNER, TeamRole.ADMIN))
        } returns ownerMember
        every { teamRepository.save(team) } returns team

        service.execute(10L, 1L)

        verify(exactly = 0) { teamGithubEventPublisher.publishDisconnected(any()) }
    }

    @Test
    fun `execute는 일반 MEMBER면 FORBIDDEN`() {
        every {
            teamMemberRepository.findByTeamIdAndUserIdAndRoleIn(1L, 42L, listOf(TeamRole.OWNER, TeamRole.ADMIN))
        } returns null

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(42L, 1L)
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
        verify(exactly = 0) { teamGithubEventPublisher.publishDisconnected(any()) }
    }
}
