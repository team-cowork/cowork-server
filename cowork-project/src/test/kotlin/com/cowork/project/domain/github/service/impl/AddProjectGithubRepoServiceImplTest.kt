package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.entity.TeamGithubInstallation
import com.cowork.project.domain.github.event.ProjectGithubRepoEventPublisher
import com.cowork.project.domain.github.presentation.data.request.AddProjectGithubRepoReqDto
import com.cowork.project.domain.github.repository.ProjectGithubRepoRepository
import com.cowork.project.domain.github.repository.TeamGithubInstallationRepository
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.GithubRepoRef
import com.cowork.project.domain.github.service.GithubSyncRequestScheduler
import com.cowork.project.domain.membership.entity.TeamMembership
import com.cowork.project.domain.membership.repository.TeamMembershipRepository
import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.repository.ProjectAccessProjectionRepository
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.project.service.ProjectAccessGuard
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class AddProjectGithubRepoServiceImplTest {

    private val projectGithubRepoRepository = mockk<ProjectGithubRepoRepository>()
    private val projectRepository = mockk<ProjectRepository>(relaxed = true)
    private val teamMembershipRepository = mockk<TeamMembershipRepository>()
    private val projectAccessGuard =
        ProjectAccessGuard(
            projectRepository,
            mockk<ProjectAccessProjectionRepository>(relaxed = true),
            teamMembershipRepository,
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
    private val repoEventPublisher = mockk<ProjectGithubRepoEventPublisher>(relaxed = true)
    private val githubSyncRequestScheduler = mockk<GithubSyncRequestScheduler>(relaxed = true)
    private val teamGithubInstallationRepository = mockk<TeamGithubInstallationRepository>().also {
        every { it.findByIdOrNull(100L) } returns
            TeamGithubInstallation(teamId = 100L, installationId = 1L, orgLogin = "my-org")
    }

    private val service = AddProjectGithubRepoServiceImpl(
        projectGithubRepoRepository,
        projectAccessGuard,
        repoEventPublisher,
        githubSyncRequestScheduler,
        GithubRepoAccessResolver(projectAccessGuard, projectGithubRepoRepository, teamGithubInstallationRepository),
    )

    private fun project(id: Long = 1L, teamId: Long = 100L) =
        Project(id = id, teamId = teamId, name = "p", description = null, createdBy = 1L)

    @Nested
    inner class Execute {
        @Test
        fun `addGithubRepo는 유효한 URL이면 저장`() {
            val proj = project()
            every { projectRepository.findByIdForUpdate(1L) } returns proj
            every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 99L) } returns
                TeamMembership(teamId = 100L, userId = 99L, role = "OWNER")
            every {
                projectGithubRepoRepository.existsByTeamIdAndGithubRepoUrl(100L, "https://github.com/my-org/my-repo")
            } returns false
            every { projectGithubRepoRepository.saveAndFlush(any()) } answers { firstArg() }

            val response = service.execute(99L, 1L, AddProjectGithubRepoReqDto("https://github.com/my-org/my-repo"))

            assertEquals("https://github.com/my-org/my-repo", response.githubRepoUrl)
            verify(exactly = 1) { githubSyncRequestScheduler.requestRepositorySync(GithubRepoRef("my-org", "my-repo")) }
        }

        @Test
        fun `addGithubRepo는 팀 내 다른 프로젝트가 이미 연결한 레포면 CONFLICT`() {
            val proj = project()
            every { projectRepository.findByIdForUpdate(1L) } returns proj
            every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 99L) } returns
                TeamMembership(teamId = 100L, userId = 99L, role = "OWNER")
            every {
                projectGithubRepoRepository.existsByTeamIdAndGithubRepoUrl(100L, "https://github.com/my-org/my-repo")
            } returns true

            val ex = assertThrows(ExpectedException::class.java) {
                service.execute(99L, 1L, AddProjectGithubRepoReqDto("https://github.com/my-org/my-repo"))
            }
            assertEquals(HttpStatus.CONFLICT, ex.statusCode)
        }

        @Test
        fun `addGithubRepo는 팀에 연결된 GitHub 조직의 레포가 아니면 FORBIDDEN`() {
            val proj = project()
            every { projectRepository.findByIdForUpdate(1L) } returns proj
            every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 99L) } returns
                TeamMembership(teamId = 100L, userId = 99L, role = "OWNER")

            val ex = assertThrows(ExpectedException::class.java) {
                service.execute(99L, 1L, AddProjectGithubRepoReqDto("https://github.com/other-org/private-repo"))
            }
            assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
            verify(exactly = 0) { projectGithubRepoRepository.saveAndFlush(any()) }
            verify(exactly = 0) { githubSyncRequestScheduler.requestRepositorySync(any()) }
        }

        @Test
        fun `addGithubRepo는 github_com이 아닌 호스트면 BAD_REQUEST`() {
            val proj = project()
            every { projectRepository.findByIdForUpdate(1L) } returns proj
            every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 99L) } returns
                TeamMembership(teamId = 100L, userId = 99L, role = "OWNER")

            val ex = assertThrows(ExpectedException::class.java) {
                service.execute(99L, 1L, AddProjectGithubRepoReqDto("https://gitlab.com/my-org/my-repo"))
            }
            assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
        }

        @Test
        fun `addGithubRepo는 팀 멤버가 아니면 FORBIDDEN`() {
            val proj = project()
            every { projectRepository.findByIdForUpdate(1L) } returns proj

            every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 50L) } returns null

            val ex = assertThrows(ExpectedException::class.java) {
                service.execute(50L, 1L, AddProjectGithubRepoReqDto("https://github.com/my-org/my-repo"))
            }
            assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
        }
    }
}
