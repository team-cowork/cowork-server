package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.team.service.support.TeamGithubStateSupport
import com.cowork.team.domain.teamMember.TeamPermissionGuard
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.global.config.TeamGithubProperties
import com.cowork.team.support.policyReader
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
    private val teamAccessGuard =
        TeamAccessGuard(
            teamRepository,
            TeamPermissionGuard(teamMemberRepository, policyReader(1, mapOf(10L to mapOf("manage_github" to true)))),
        )
    private val teamGithubStateSupport = mockk<TeamGithubStateSupport>()
    private val teamGithubProperties = TeamGithubProperties(stateSecret = "secret", appSlug = "my-app")

    private val service =
        GenerateGithubInstallUrlServiceImpl(teamAccessGuard, teamGithubStateSupport, teamGithubProperties)

    @Test
    fun `execute는 OWNER_ADMIN이면 설치 URL을 발급한다`() {
        every {
            teamMemberRepository.existsByTeamIdAndUserId(1L, 10L)
        } returns true
        every { teamGithubStateSupport.buildState(1L, 10L) } returns "signed-state"

        val result = service.execute(10L, 1L)

        assertEquals("https://github.com/apps/my-app/installations/new?state=signed-state", result.installUrl)
    }

    @Test
    fun `execute는 일반 MEMBER면 FORBIDDEN`() {
        every {
            teamMemberRepository.existsByTeamIdAndUserId(1L, 42L)
        } returns false

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(42L, 1L)
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }
}
