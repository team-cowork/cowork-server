package com.cowork.project

import com.cowork.global.error.BusinessException
import com.cowork.project.domain.Project
import com.cowork.project.domain.ProjectMember
import com.cowork.project.domain.ProjectMemberRole
import com.cowork.project.repository.ProjectMemberRepository
import com.cowork.project.repository.ProjectRepository
import com.cowork.project.service.ProjectAccessChecker
import com.cowork.team.domain.TeamMember
import com.cowork.team.domain.TeamMemberRole
import com.cowork.team.domain.TeamPermission
import com.cowork.team.service.TeamAccessChecker
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import java.util.Optional

class ProjectAccessCheckerTest {

    private val projectRepository = mockk<ProjectRepository>()
    private val projectMemberRepository = mockk<ProjectMemberRepository>()
    private val teamAccessChecker = mockk<TeamAccessChecker>()
    private val checker = ProjectAccessChecker(projectRepository, projectMemberRepository, teamAccessChecker)

    private val project = Project(
        teamId = 1, name = "cowork", description = null, githubRepoUrl = null, position = 0, createdBy = 1, id = 20,
    )
    private val teamMember = TeamMember(teamId = 1, userId = 5, role = TeamMemberRole.MEMBER)

    @BeforeEach
    fun setUp() {
        every { projectRepository.findById(20) } returns Optional.of(project)
        every { teamAccessChecker.requireMember(1, 5) } returns teamMember
        every { teamAccessChecker.hasPermission(teamMember, TeamPermission.MANAGE_PROJECTS) } returns false
    }

    private fun projectRole(role: ProjectMemberRole?) {
        every { projectMemberRepository.findByProjectIdAndUserId(20, 5) } returns
            role?.let { ProjectMember(projectId = 20, userId = 5, role = it) }
    }

    @Test
    fun `존재하지 않는 프로젝트는 404를 반환한다`() {
        every { projectRepository.findById(99) } returns Optional.empty()

        val exception = assertThrows<BusinessException> { checker.requireReadable(99, 5) }

        assertEquals(HttpStatus.NOT_FOUND, exception.status)
    }

    @Test
    fun `팀 멤버는 프로젝트 멤버가 아니어도 조회할 수 있다`() {
        assertEquals(project, checker.requireReadable(20, 5))
    }

    @Test
    fun `편집자는 프로젝트를 수정할 수 있다`() {
        projectRole(ProjectMemberRole.EDITOR)

        assertDoesNotThrow { checker.requireEditable(20, 5) }
    }

    @Test
    fun `뷰어는 프로젝트를 수정할 수 없다`() {
        projectRole(ProjectMemberRole.VIEWER)

        val exception = assertThrows<BusinessException> { checker.requireEditable(20, 5) }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)
    }
}
