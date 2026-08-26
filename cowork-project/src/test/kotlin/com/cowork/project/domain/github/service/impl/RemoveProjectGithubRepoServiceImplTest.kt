package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.entity.ProjectGithubRepo
import com.cowork.project.domain.github.repository.ProjectGithubRepoRepository
import com.cowork.project.domain.membership.repository.TeamMembershipRepository
import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.projectMember.entity.ProjectMember
import com.cowork.project.domain.projectMember.entity.ProjectMemberRole
import com.cowork.project.domain.projectMember.repository.ProjectMemberRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.util.Optional

class RemoveProjectGithubRepoServiceImplTest {

    private val projectGithubRepoRepository = mockk<ProjectGithubRepoRepository>(relaxed = true)
    private val projectRepository = mockk<ProjectRepository>(relaxed = true)
    private val projectMemberRepository = mockk<ProjectMemberRepository>(relaxed = true)
    private val teamMembershipRepository = mockk<TeamMembershipRepository>()
    private val projectAccessGuard =
        ProjectAccessGuard(projectRepository, projectMemberRepository, teamMembershipRepository, mockk(relaxed = true))

    private val service = RemoveProjectGithubRepoServiceImpl(projectGithubRepoRepository, projectAccessGuard)

    private fun project(id: Long = 1L, teamId: Long = 100L) =
        Project(id = id, teamId = teamId, name = "p", description = null, createdBy = 1L)

    private fun repoLink(id: Long = 5L, projectId: Long = 1L, teamId: Long = 100L) = ProjectGithubRepo(
        id = id,
        projectId = projectId,
        teamId = teamId,
        githubRepoUrl = "https://github.com/my-org/my-repo",
    )

    @Test
    fun `removeGithubRepo는 등록된 레포를 삭제`() {
        val proj = project()
        val link = repoLink()
        every { projectRepository.findById(1L) } returns Optional.of(proj)
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 99L) } returns
            ProjectMember(projectId = 1L, userId = 99L, role = ProjectMemberRole.OWNER)
        every { projectGithubRepoRepository.findByIdAndProjectId(5L, 1L) } returns link

        service.execute(99L, 1L, 5L)

        verify { projectGithubRepoRepository.delete(link) }
    }

    @Test
    fun `removeGithubRepo는 등록된 레포가 없으면 NOT_FOUND`() {
        val proj = project()
        every { projectRepository.findById(1L) } returns Optional.of(proj)
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 99L) } returns
            ProjectMember(projectId = 1L, userId = 99L, role = ProjectMemberRole.OWNER)
        every { projectGithubRepoRepository.findByIdAndProjectId(5L, 1L) } returns null

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(99L, 1L, 5L)
        }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }

    @Test
    fun `removeGithubRepo는 EDITOR가 아니면 FORBIDDEN`() {
        val proj = project()
        every { projectRepository.findById(1L) } returns Optional.of(proj)
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 50L) } returns
            ProjectMember(projectId = 1L, userId = 50L, role = ProjectMemberRole.VIEWER)
        every { teamMembershipRepository.findByTeamIdAndUserId(100L, 50L) } returns null

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(50L, 1L, 5L)
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }
}
