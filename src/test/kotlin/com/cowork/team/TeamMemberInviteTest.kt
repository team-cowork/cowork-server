package com.cowork.team

import com.cowork.global.error.BusinessException
import com.cowork.team.domain.Team
import com.cowork.team.domain.TeamMember
import com.cowork.team.domain.TeamMemberRole
import com.cowork.team.domain.TeamPermission
import com.cowork.team.domain.TeamVisibility
import com.cowork.team.event.TeamMemberAddedEvent
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

class TeamMemberInviteTest {

    private val teamRepository = mockk<TeamRepository>()
    private val memberRepository = mockk<TeamMemberRepository>(relaxed = true)
    private val accessChecker = mockk<TeamAccessChecker>()
    private val userService = mockk<UserService>(relaxed = true)
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val service = TeamMemberService(
        teamRepository, memberRepository, mockk<TeamRoleAssignmentRepository>(relaxed = true), accessChecker, userService, eventPublisher,
    )

    private val owner = TeamMember(teamId = 1, userId = 2, role = TeamMemberRole.OWNER)

    @Test
    fun `가입하지 않은 사용자는 초대할 수 없다`() {
        every { accessChecker.requirePermission(1, 2, TeamPermission.INVITE_MEMBERS) } returns owner
        every { userService.exists(9) } returns false

        val exception = assertThrows<BusinessException> { service.addMember(1, 2, 9) }

        assertEquals(HttpStatus.NOT_FOUND, exception.status)
    }

    @Test
    fun `초대하면 일반 멤버로 추가하고 초대 이벤트를 발행한다`() {
        every { accessChecker.requirePermission(1, 2, TeamPermission.INVITE_MEMBERS) } returns owner
        every { userService.exists(9) } returns true
        every { memberRepository.existsByTeamIdAndUserId(1, 9) } returns false
        every { teamRepository.findById(1) } returns
            Optional.of(Team(name = "cowork", description = null, visibility = TeamVisibility.PUBLIC, ownerId = 2, id = 1))
        every { memberRepository.save(any()) } answers { firstArg() }

        val response = service.addMember(1, 2, 9)

        assertEquals(TeamMemberRole.MEMBER, response.role)
        verify { eventPublisher.publishEvent(TeamMemberAddedEvent(1, "cowork", 9, 2)) }
    }
}
