package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.membership.entity.TeamMembership
import com.cowork.project.domain.membership.repository.TeamMembershipRepository
import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.event.ProjectEventPublisher
import com.cowork.project.domain.project.presentation.data.request.UpdateProjectReqDto
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.projectMember.repository.ProjectMemberRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class UpdateProjectServiceImplTest {

    private val projectRepository = mockk<ProjectRepository>(relaxed = true)
    private val projectMemberRepository = mockk<ProjectMemberRepository>(relaxed = true)
    private val teamMembershipRepository = mockk<TeamMembershipRepository>()
    private val projectEventPublisher = mockk<ProjectEventPublisher>(relaxed = true)
    private val projectAccessGuard =
        ProjectAccessGuard(projectRepository, projectMemberRepository, teamMembershipRepository, mockk(relaxed = true))

    private val service = UpdateProjectServiceImpl(projectEventPublisher, projectAccessGuard)

    private fun project(id: Long = 1L, teamId: Long = 100L, position: Int = 0) =
        Project(id = id, teamId = teamId, name = "p", description = null, position = position, createdBy = 1L)

    private fun membership(teamId: Long, userId: Long, role: String = "MEMBER") =
        TeamMembership(teamId = teamId, userId = userId, role = role)

    @Test
    fun `updateProject은 팀 OWNER 등가 권한으로 통과`() {
        val proj = project()
        every { projectRepository.findByIdForUpdate(1L) } returns proj
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 99L) } returns null
        every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 99L) } returns membership(100L, 99L, "OWNER")

        val response = service.execute(99L, 1L, UpdateProjectReqDto(name = "newName"))
        assertEquals("newName", response.name)
    }

    @Test
    fun `updateProject은 팀 비멤버이면 FORBIDDEN`() {
        val proj = project()
        every { projectRepository.findByIdForUpdate(1L) } returns proj
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 99L) } returns null
        every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 99L) } returns null

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(99L, 1L, UpdateProjectReqDto(name = "x"))
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }
}
