package com.cowork.project

import com.cowork.global.error.BusinessException
import com.cowork.project.domain.Project
import com.cowork.project.repository.ProjectMemberRepository
import com.cowork.project.repository.ProjectRepository
import com.cowork.project.service.ProjectAccessChecker
import com.cowork.project.service.ProjectService
import com.cowork.team.domain.TeamMember
import com.cowork.team.domain.TeamMemberRole
import com.cowork.team.domain.TeamPermission
import com.cowork.team.service.TeamAccessChecker
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus

class ProjectServiceTest {

    private val projectRepository = mockk<ProjectRepository>()
    private val teamAccessChecker = mockk<TeamAccessChecker>()
    private val service = ProjectService(
        projectRepository,
        mockk<ProjectMemberRepository>(relaxed = true),
        mockk<ProjectAccessChecker>(),
        teamAccessChecker,
        mockk<ApplicationEventPublisher>(relaxed = true),
    )

    private fun project(id: Long, position: Int) =
        Project(teamId = 1, name = "p$id", description = null, githubRepoUrl = null, position = position, createdBy = 1, id = id)

    @Test
    fun `프로젝트 순서를 요청한 순서대로 바꾼다`() {
        val projects = listOf(project(10, 0), project(11, 1), project(12, 2))
        every { teamAccessChecker.requirePermission(1, 1, TeamPermission.MANAGE_PROJECTS) } returns
            TeamMember(teamId = 1, userId = 1, role = TeamMemberRole.ADMIN)
        every { projectRepository.findAllByTeamIdOrderByPositionAscIdAsc(1) } returns projects

        val result = service.reorder(1, 1, listOf(12, 10, 11))

        assertEquals(listOf(12L, 10L, 11L), result.map { it.id })
        assertEquals(listOf(1, 2, 0), projects.map { it.position })
    }

    @Test
    fun `팀의 프로젝트 일부만 전달하면 순서를 바꿀 수 없다`() {
        every { teamAccessChecker.requirePermission(1, 1, TeamPermission.MANAGE_PROJECTS) } returns
            TeamMember(teamId = 1, userId = 1, role = TeamMemberRole.ADMIN)
        every { projectRepository.findAllByTeamIdOrderByPositionAscIdAsc(1) } returns listOf(project(10, 0), project(11, 1))

        val exception = assertThrows<BusinessException> { service.reorder(1, 1, listOf(10, 10)) }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
    }
}
