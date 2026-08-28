package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.entity.ProjectGithubRepo
import com.cowork.project.domain.github.repository.ProjectGithubRepoRepository
import com.cowork.project.domain.membership.entity.TeamMembership
import com.cowork.project.domain.membership.repository.TeamMembershipRepository
import com.cowork.project.domain.project.entity.Project
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
import java.util.Optional

class ListProjectGithubRepoLinksServiceImplTest {

    private val projectGithubRepoRepository = mockk<ProjectGithubRepoRepository>()
    private val projectRepository = mockk<ProjectRepository>(relaxed = true)
    private val projectMemberRepository = mockk<ProjectMemberRepository>(relaxed = true)
    private val teamMembershipRepository = mockk<TeamMembershipRepository>()
    private val projectAccessGuard =
        ProjectAccessGuard(projectRepository, projectMemberRepository, teamMembershipRepository, mockk(relaxed = true))

    private val service = ListProjectGithubRepoLinksServiceImpl(projectGithubRepoRepository, projectAccessGuard)

    private fun project(id: Long = 1L, teamId: Long = 100L) =
        Project(id = id, teamId = teamId, name = "p", description = null, createdBy = 1L)

    @Test
    fun `listGithubRepos는 팀 멤버면 등록된 레포 목록을 반환`() {
        val proj = project()
        every { projectRepository.findById(1L) } returns Optional.of(proj)
        every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 7L) } returns
            TeamMembership(teamId = 100L, userId = 7L, role = "MEMBER")
        every { projectGithubRepoRepository.findAllByProjectId(1L) } returns
            listOf(
                ProjectGithubRepo(
                    id = 1L,
                    projectId = 1L,
                    teamId = 100L,
                    githubRepoUrl = "https://github.com/my-org/my-repo",
                ),
            )

        val result = service.execute(7L, 1L)

        assertEquals(1, result.size)
        assertEquals("https://github.com/my-org/my-repo", result[0].githubRepoUrl)
    }

    @Test
    fun `listGithubRepos는 팀 멤버가 아니면 FORBIDDEN`() {
        val proj = project()
        every { projectRepository.findById(1L) } returns Optional.of(proj)
        every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 7L) } returns null

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(7L, 1L)
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }
}
