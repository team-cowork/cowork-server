package com.cowork.team

import com.cowork.global.error.BusinessException
import com.cowork.team.domain.Team
import com.cowork.team.domain.TeamMember
import com.cowork.team.domain.TeamMemberRole
import com.cowork.team.domain.TeamPermission
import com.cowork.team.domain.TeamVisibility
import com.cowork.team.event.TeamMemberRemovedEvent
import com.cowork.team.repository.TeamMemberRepository
import com.cowork.team.repository.TeamRepository
import com.cowork.team.repository.TeamRoleAssignmentRepository
import com.cowork.team.service.TeamAccessChecker
import com.cowork.team.service.TeamMemberService
import com.cowork.user.service.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import java.util.Optional

class TeamMemberServiceTest {

    private val teamRepository = mockk<TeamRepository>()
    private val teamMemberRepository = mockk<TeamMemberRepository>(relaxed = true)
    private val assignmentRepository = mockk<TeamRoleAssignmentRepository>(relaxed = true)
    private val accessChecker = mockk<TeamAccessChecker>()
    private val userService = mockk<UserService>()
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val service = TeamMemberService(
        teamRepository,
        teamMemberRepository,
        assignmentRepository,
        accessChecker,
        userService,
        eventPublisher,
    )

    private fun member(userId: Long, role: TeamMemberRole) = TeamMember(teamId = 1, userId = userId, role = role)

    @Test
    fun `팀 소유자는 팀을 떠날 수 없다`() {
        val owner = member(1, TeamMemberRole.OWNER)
        every { accessChecker.requireMember(1, 1) } returns owner
        every { teamMemberRepository.findByTeamIdAndUserId(1, 1) } returns owner

        val exception = assertThrows<BusinessException> { service.removeMember(1, 1, 1) }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
    }

    @Test
    fun `소유자가 아닌 관리자는 다른 관리자를 내보낼 수 없다`() {
        val actor = member(2, TeamMemberRole.ADMIN)
        val target = member(3, TeamMemberRole.ADMIN)
        every { accessChecker.requireMember(1, 2) } returns actor
        every { teamMemberRepository.findByTeamIdAndUserId(1, 3) } returns target
        every { accessChecker.hasPermission(actor, TeamPermission.KICK_MEMBERS) } returns true

        val exception = assertThrows<BusinessException> { service.removeMember(1, 2, 3) }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)
    }

    @Test
    fun `멤버는 스스로 팀을 떠날 수 있고 이벤트가 발행된다`() {
        val self = member(4, TeamMemberRole.MEMBER)
        every { accessChecker.requireMember(1, 4) } returns self
        every { teamMemberRepository.findByTeamIdAndUserId(1, 4) } returns self

        service.removeMember(1, 4, 4)

        verify { assignmentRepository.deleteAllByTeamIdAndUserId(1, 4) }
        verify { teamMemberRepository.delete(self) }
        verify { eventPublisher.publishEvent(TeamMemberRemovedEvent(1, 4)) }
    }

    @Test
    fun `권한이 없는 멤버는 다른 멤버를 내보낼 수 없다`() {
        val actor = member(5, TeamMemberRole.MEMBER)
        every { accessChecker.requireMember(1, 5) } returns actor
        every { teamMemberRepository.findByTeamIdAndUserId(1, 6) } returns member(6, TeamMemberRole.MEMBER)
        every { accessChecker.hasPermission(actor, TeamPermission.KICK_MEMBERS) } returns false

        val exception = assertThrows<BusinessException> { service.removeMember(1, 5, 6) }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)
    }

    @Test
    fun `이미 팀에 속한 사용자는 다시 초대할 수 없다`() {
        every { accessChecker.requirePermission(1, 1, TeamPermission.INVITE_MEMBERS) } returns member(1, TeamMemberRole.OWNER)
        every { userService.exists(7) } returns true
        every { teamMemberRepository.existsByTeamIdAndUserId(1, 7) } returns true

        val exception = assertThrows<BusinessException> { service.addMember(1, 1, 7) }

        assertEquals(HttpStatus.CONFLICT, exception.status)
    }

    @Test
    fun `소유권을 이전하면 기존 소유자는 관리자가 된다`() {
        val owner = member(1, TeamMemberRole.OWNER)
        val target = member(2, TeamMemberRole.ADMIN)
        val team = Team(
            name = "cowork",
            description = null,
            visibility = TeamVisibility.PUBLIC,
            ownerId = 1,
            id = 1,
        )
        every { accessChecker.requireOwner(1, 1) } returns owner
        every { teamMemberRepository.findByTeamIdAndUserId(1, 2) } returns target
        every { teamRepository.findById(1) } returns Optional.of(team)
        every { userService.getSummaries(any()) } returns emptyMap()

        service.changeRole(1, 1, 2, TeamMemberRole.OWNER)

        assertEquals(TeamMemberRole.ADMIN, owner.role)
        assertEquals(TeamMemberRole.OWNER, target.role)
        assertEquals(2, team.ownerId)
    }
}
