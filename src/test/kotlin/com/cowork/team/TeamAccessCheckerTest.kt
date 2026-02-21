package com.cowork.team

import com.cowork.global.error.BusinessException
import com.cowork.team.domain.TeamMember
import com.cowork.team.domain.TeamMemberRole
import com.cowork.team.domain.TeamPermission
import com.cowork.team.domain.TeamRole
import com.cowork.team.repository.TeamMemberRepository
import com.cowork.team.repository.TeamRepository
import com.cowork.team.repository.TeamRoleRepository
import com.cowork.team.service.TeamAccessChecker
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `관리자는 소유자 전용 작업을 할 수 없다`() {
        every { teamRepository.existsById(1) } returns true
        every { teamMemberRepository.findByTeamIdAndUserId(1, 5) } returns member(TeamMemberRole.ADMIN)

        assertThrows<BusinessException> { checker.requireOwner(1, 5) }
    }

    @Test
    fun `소유자는 소유자 전용 작업을 할 수 있다`() {
        every { teamRepository.existsById(1) } returns true
        every { teamMemberRepository.findByTeamIdAndUserId(1, 5) } returns member(TeamMemberRole.OWNER)

        assertEquals(TeamMemberRole.OWNER, checker.requireOwner(1, 5).role)
    }

    @Test
    fun `관리자 이상은 역할 조회 없이 모든 권한을 가진다`() {
        assertTrue(checker.hasPermission(member(TeamMemberRole.ADMIN), TeamPermission.MANAGE_ROLES))
        verify(exactly = 0) { teamRoleRepository.findAllAssignedTo(any(), any()) }
    }

    @Test
    fun `일반 멤버는 부여받은 역할의 권한만 가진다`() {
        every { teamRoleRepository.findAllAssignedTo(1, 5) } returns
            listOf(TeamRole(teamId = 1, name = "기획", permissions = setOf(TeamPermission.MANAGE_PROJECTS)))
        val member = member(TeamMemberRole.MEMBER)

        assertTrue(checker.hasPermission(member, TeamPermission.MANAGE_PROJECTS))
        assertFalse(checker.hasPermission(member, TeamPermission.KICK_MEMBERS))
    }

    @Test
    fun `권한이 없으면 403을 반환한다`() {
        every { teamRepository.existsById(1) } returns true
        every { teamMemberRepository.findByTeamIdAndUserId(1, 5) } returns member(TeamMemberRole.MEMBER)
        every { teamRoleRepository.findAllAssignedTo(1, 5) } returns emptyList()

        val exception = assertThrows<BusinessException> {
            checker.requirePermission(1, 5, TeamPermission.INVITE_MEMBERS)
        }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)
    }
}
