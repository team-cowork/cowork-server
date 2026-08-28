package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.entity.ProjectGithubRepo
import com.cowork.project.domain.github.event.ProjectGithubRepoEventPublisher
import com.cowork.project.domain.github.presentation.data.request.AddProjectGithubRepoReqDto
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
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class AddProjectGithubRepoServiceImplTest {

    private val projectGithubRepoRepository = mockk<ProjectGithubRepoRepository>()
    private val projectRepository = mockk<ProjectRepository>(relaxed = true)
    private val projectMemberRepository = mockk<ProjectMemberRepository>(relaxed = true)
    private val teamMembershipRepository = mockk<TeamMembershipRepository>()
    private val projectAccessGuard =
        ProjectAccessGuard(projectRepository, projectMemberRepository, teamMembershipRepository, mockk(relaxed = true))
    private val repoEventPublisher = mockk<ProjectGithubRepoEventPublisher>(relaxed = true)

    private val service =
        AddProjectGithubRepoServiceImpl(projectGithubRepoRepository, projectAccessGuard, repoEventPublisher)

    private fun project(id: Long = 1L, teamId: Long = 100L) =
        Project(id = id, teamId = teamId, name = "p", description = null, createdBy = 1L)

    @Test
    fun `addGithubRepo는 유효한 URL이면 저장`() {
        val proj = project()
        every { projectRepository.findByIdForUpdate(1L) } returns proj
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 99L) } returns
            ProjectMember(projectId = 1L, userId = 99L, role = ProjectMemberRole.OWNER)
        every {
            projectGithubRepoRepository.existsByTeamIdAndGithubRepoUrl(100L, "https://github.com/my-org/my-repo")
        } returns false
        every { projectGithubRepoRepository.saveAndFlush(any()) } answers { firstArg() }

        val response = service.execute(99L, 1L, AddProjectGithubRepoReqDto("https://github.com/my-org/my-repo"))

        assertEquals("https://github.com/my-org/my-repo", response.githubRepoUrl)
        verify(exactly = 1) { repoEventPublisher.publishUpsert(any(), any()) }
    }

    @Test
    fun `addGithubRepo는 팀 내 다른 프로젝트가 이미 연결한 레포면 CONFLICT`() {
        val proj = project()
        every { projectRepository.findByIdForUpdate(1L) } returns proj
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 99L) } returns
            ProjectMember(projectId = 1L, userId = 99L, role = ProjectMemberRole.OWNER)
        every {
            projectGithubRepoRepository.existsByTeamIdAndGithubRepoUrl(100L, "https://github.com/my-org/my-repo")
        } returns true

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(99L, 1L, AddProjectGithubRepoReqDto("https://github.com/my-org/my-repo"))
        }
        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
    }

    @Test
    fun `addGithubRepo는 github_com이 아닌 호스트면 BAD_REQUEST`() {
        val proj = project()
        every { projectRepository.findByIdForUpdate(1L) } returns proj
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 99L) } returns
            ProjectMember(projectId = 1L, userId = 99L, role = ProjectMemberRole.OWNER)

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(99L, 1L, AddProjectGithubRepoReqDto("https://gitlab.com/my-org/my-repo"))
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `addGithubRepo는 EDITOR가 아니면 FORBIDDEN`() {
        val proj = project()
        every { projectRepository.findByIdForUpdate(1L) } returns proj
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 50L) } returns
            ProjectMember(projectId = 1L, userId = 50L, role = ProjectMemberRole.VIEWER)
        every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 50L) } returns null

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(50L, 1L, AddProjectGithubRepoReqDto("https://github.com/my-org/my-repo"))
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    @Test
    fun `addGithubRepo는 동시 등록으로 유니크 제약을 위반하면 CONFLICT`() {
        val proj = project()
        every { projectRepository.findByIdForUpdate(1L) } returns proj
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 99L) } returns
            ProjectMember(projectId = 1L, userId = 99L, role = ProjectMemberRole.OWNER)
        every {
            projectGithubRepoRepository.existsByTeamIdAndGithubRepoUrl(100L, "https://github.com/my-org/my-repo")
        } returns false
        every { projectGithubRepoRepository.saveAndFlush(any<ProjectGithubRepo>()) } throws
            DataIntegrityViolationException("duplicate")

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(99L, 1L, AddProjectGithubRepoReqDto("https://github.com/my-org/my-repo"))
        }
        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
    }
}
