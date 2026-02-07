package com.cowork.team

import com.cowork.global.error.BusinessException
import com.cowork.team.domain.TeamMember
import com.cowork.team.domain.TeamMemberRole
import com.cowork.team.repository.TeamMemberRepository
import com.cowork.team.repository.TeamRepository
import com.cowork.team.repository.TeamRoleRepository
import com.cowork.team.service.TeamAccessChecker
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus

class TeamAccessCheckerTest {

    private val teamRepository = mockk<TeamRepository>()
    private val teamMemberRepository = mockk<TeamMemberRepository>()
    private val teamRoleRepository = mockk<TeamRoleRepository>()
    private val checker = TeamAccessChecker(teamRepository, teamMemberRepository, teamRoleRepository)

    private fun member(role: TeamMemberRole) = TeamMember(teamId = 1, userId = 5, role = role, id = 10)

    @Test
    fun `존재하지 않는 팀은 404를 반환한다`() {
        every { teamRepository.existsById(1) } returns false

        val exception = assertThrows<BusinessException> { checker.requireMember(1, 5) }

        assertEquals(HttpStatus.NOT_FOUND, exception.status)
    }

    @Test
    fun `팀 멤버가 아니면 403을 반환한다`() {
        every { teamRepository.existsById(1) } returns true
        every { teamMemberRepository.findByTeamIdAndUserId(1, 5) } returns null

        val exception = assertThrows<BusinessException> { checker.requireMember(1, 5) }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)
    }
}
