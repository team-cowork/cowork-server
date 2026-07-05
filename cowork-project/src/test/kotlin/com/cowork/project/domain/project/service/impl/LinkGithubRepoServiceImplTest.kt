package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.github.presentation.data.request.LinkGithubRepoReqDto
import com.cowork.project.domain.membership.repository.TeamMembershipRepository
import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.projectMember.entity.ProjectMember
import com.cowork.project.domain.projectMember.entity.ProjectMemberRole
import com.cowork.project.domain.projectMember.repository.ProjectMemberRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.util.Optional

class LinkGithubRepoServiceImplTest {

    private val projectRepository = mockk<ProjectRepository>(relaxed = true)
    private val projectMemberRepository = mockk<ProjectMemberRepository>(relaxed = true)
    private val teamMembershipRepository = mockk<TeamMembershipRepository>()
    private val projectAccessGuard = ProjectAccessGuard(projectRepository, projectMemberRepository, teamMembershipRepository)

    private val service = LinkGithubRepoServiceImpl(projectMemberRepository, projectAccessGuard)

    private fun project(id: Long = 1L, teamId: Long = 100L, position: Int = 0) =
        Project(id = id, teamId = teamId, name = "p", description = null, position = position, createdBy = 1L)

    @Test
    fun `linkGithubRepo는 유효한 URL이면 저장`() {
        val proj = project()
        every { projectRepository.findById(1L) } returns Optional.of(proj)
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 99L) } returns
            ProjectMember(projectId = 1L, userId = 99L, role = ProjectMemberRole.OWNER)

        val response = service.linkGithubRepo(99L, 1L, LinkGithubRepoReqDto("https://github.com/my-org/my-repo"))

        assertEquals("https://github.com/my-org/my-repo", response.githubRepoUrl)
    }

    @Test
    fun `linkGithubRepo는 github_com이 아닌 호스트면 BAD_REQUEST`() {
        val proj = project()
        every { projectRepository.findById(1L) } returns Optional.of(proj)
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 99L) } returns
            ProjectMember(projectId = 1L, userId = 99L, role = ProjectMemberRole.OWNER)

        val ex = assertThrows(ExpectedException::class.java) {
            service.linkGithubRepo(99L, 1L, LinkGithubRepoReqDto("https://gitlab.com/my-org/my-repo"))
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `linkGithubRepo는 EDITOR가 아니면 FORBIDDEN`() {
        val proj = project()
        every { projectRepository.findById(1L) } returns Optional.of(proj)
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 50L) } returns
            ProjectMember(projectId = 1L, userId = 50L, role = ProjectMemberRole.VIEWER)
        every { teamMembershipRepository.findByTeamIdAndUserId(100L, 50L) } returns null

        val ex = assertThrows(ExpectedException::class.java) {
            service.linkGithubRepo(50L, 1L, LinkGithubRepoReqDto("https://github.com/my-org/my-repo"))
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }
}
