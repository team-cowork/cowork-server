package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.team.service.support.TeamGithubStateSupport
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamRole.entity.TeamRole
import com.cowork.team.global.config.TeamGithubProperties
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class GenerateGithubInstallUrlServiceImplTest {

    private val teamRepository = mockk<TeamRepository>()
    private val teamMemberRepository = mockk<TeamMemberRepository>()
    private val teamAccessGuard = TeamAccessGuard(teamRepository, teamMemberRepository)
    private val teamGithubStateSupport = mockk<TeamGithubStateSupport>()
    private val teamGithubProperties = TeamGithubProperties(stateSecret = "secret", appSlug = "my-app")

    private val service =
        GenerateGithubInstallUrlServiceImpl(teamAccessGuard, teamGithubStateSupport, teamGithubProperties)

    private val team = Team(id = 1L, name = "테스트팀", description = null, iconUrl = null, ownerId = 10L)

    @Test
    fun `execute는 OWNER_ADMIN이면 설치 URL을 발급한다`() {
        val ownerMember = TeamMember(team = team, userId = 10L, role = TeamRole.OWNER)
        every {
            teamMemberRepository.findByTeamIdAndUserIdAndRoleIn(1L, 10L, listOf(TeamRole.OWNER, TeamRole.ADMIN))
        } returns ownerMember
        every { teamGithubStateSupport.buildState(1L, 10L) } returns "signed-state"

        val result = service.execute(10L, 1L)

        assertEquals("https://github.com/apps/my-app/installations/new?state=signed-state", result.installUrl)
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
    }
}
