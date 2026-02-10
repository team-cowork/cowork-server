package com.cowork.team

import com.cowork.global.error.BusinessException
import com.cowork.global.storage.StorageService
import com.cowork.team.domain.Team
import com.cowork.team.domain.TeamVisibility
import com.cowork.team.repository.TeamMemberRepository
import com.cowork.team.repository.TeamRepository
import com.cowork.team.repository.TeamRoleAssignmentRepository
import com.cowork.team.repository.TeamRoleRepository
import com.cowork.team.service.TeamAccessChecker
import com.cowork.team.service.TeamService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import java.util.Optional

class TeamServiceTest {

    private val teamRepository = mockk<TeamRepository>(relaxed = true)
    private val teamMemberRepository = mockk<TeamMemberRepository>(relaxed = true)
    private val teamRoleRepository = mockk<TeamRoleRepository>(relaxed = true)
    private val assignmentRepository = mockk<TeamRoleAssignmentRepository>(relaxed = true)
    private val accessChecker = mockk<TeamAccessChecker>()
    private val storageService = mockk<StorageService>(relaxed = true)
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val service = TeamService(
        teamRepository,
        teamMemberRepository,
        teamRoleRepository,
        assignmentRepository,
        accessChecker,
        storageService,
        eventPublisher,
    )

    private fun team(visibility: TeamVisibility) =
        Team(name = "cowork", description = null, visibility = visibility, ownerId = 1, id = 1)

    @Test
    fun `비공개 팀은 멤버가 아니면 조회할 수 없다`() {
        every { teamRepository.findById(1) } returns Optional.of(team(TeamVisibility.PRIVATE))
        every { teamMemberRepository.findByTeamIdAndUserId(1, 9) } returns null

        val exception = assertThrows<BusinessException> { service.getTeam(1, 9) }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)
    }

    @Test
    fun `공개 팀은 멤버가 아니어도 조회할 수 있다`() {
        every { teamRepository.findById(1) } returns Optional.of(team(TeamVisibility.PUBLIC))
        every { teamMemberRepository.findByTeamIdAndUserId(1, 9) } returns null
        every { teamMemberRepository.countByTeamId(1) } returns 3

        val response = service.getTeam(1, 9)

        assertEquals(null, response.myRole)
        assertEquals(3, response.memberCount)
    }
}
