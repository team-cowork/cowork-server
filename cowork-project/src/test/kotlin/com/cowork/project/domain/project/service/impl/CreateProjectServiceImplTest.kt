package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.membership.entity.TeamMembership
import com.cowork.project.domain.membership.repository.TeamMembershipRepository
import com.cowork.project.domain.project.event.ProjectEventPublisher
import com.cowork.project.domain.project.presentation.data.request.CreateProjectReqDto
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.projectMember.event.ProjectMemberEventPublisher
import com.cowork.project.domain.projectMember.repository.ProjectMemberRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class CreateProjectServiceImplTest {

    private val projectRepository = mockk<ProjectRepository>(relaxed = true)
    private val projectMemberRepository = mockk<ProjectMemberRepository>(relaxed = true)
    private val teamMembershipRepository = mockk<TeamMembershipRepository>()
    private val projectEventPublisher = mockk<ProjectEventPublisher>(relaxed = true)
    private val projectMemberEventPublisher = mockk<ProjectMemberEventPublisher>(relaxed = true)
    private val projectAccessGuard =
        ProjectAccessGuard(projectRepository, projectMemberRepository, teamMembershipRepository, mockk(relaxed = true))

    private val service =
        CreateProjectServiceImpl(
            projectRepository,
            projectMemberRepository,
            projectEventPublisher,
            projectMemberEventPublisher,
            projectAccessGuard,
        )

    private fun membership(teamId: Long, userId: Long, role: String = "MEMBER") =
        TeamMembership(teamId = teamId, userId = userId, role = role)

    @Test
    fun `createProject은 팀 멤버 아니면 FORBIDDEN`() {
        every { teamMembershipRepository.findByTeamIdAndUserId(100L, 7L) } returns null

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(7L, CreateProjectReqDto(teamId = 100L, name = "p", description = null))
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
        verify(exactly = 0) { projectRepository.save(any()) }
    }

    @Test
    fun `createProject은 마지막 position 다음 값으로 저장`() {
        every { teamMembershipRepository.findByTeamIdAndUserId(100L, 7L) } returns membership(100L, 7L)
        every { projectRepository.findMaxPositionByTeamId(100L) } returns 3
        every { projectRepository.save(any()) } answers { firstArg() }
        every { projectMemberRepository.save(any()) } answers { firstArg() }

        val response = service.execute(7L, CreateProjectReqDto(teamId = 100L, name = "p", description = null))

        assertEquals(4, response.position)
    }

    @Test
    fun `createProject은 현재 트랜잭션에서 소유자 멤버십 이벤트를 기록`() {
        every { teamMembershipRepository.findByTeamIdAndUserId(100L, 7L) } returns membership(100L, 7L)
        every { projectRepository.findMaxPositionByTeamId(100L) } returns 0
        every { projectRepository.save(any()) } answers { firstArg() }
        every { projectMemberRepository.save(any()) } answers { firstArg() }

        service.execute(7L, CreateProjectReqDto(teamId = 100L, name = "p", description = null))

        verify(exactly = 1) { projectMemberEventPublisher.publishAdded(match { it.userId == 7L }, any()) }
    }
}
