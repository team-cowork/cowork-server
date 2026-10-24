package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.channel.service.ChannelProjectionReader
import com.cowork.project.domain.github.entity.ProjectGithubRepo
import com.cowork.project.domain.github.event.ProjectGithubRepoEventPublisher
import com.cowork.project.domain.github.repository.ProjectGithubRepoRepository
import com.cowork.project.domain.membership.entity.TeamMembership
import com.cowork.project.domain.membership.repository.TeamMembershipRepository
import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.presentation.data.request.SetProjectGithubWebhookChannelReqDto
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.project.repository.ProjectAccessProjectionRepository
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class SetProjectGithubWebhookChannelServiceImplTest {

    private val projectGithubRepoRepository = mockk<ProjectGithubRepoRepository>(relaxed = true)
    private val projectRepository = mockk<ProjectRepository>(relaxed = true)
    private val teamMembershipRepository = mockk<TeamMembershipRepository>()
    private val channelProjectionReader = mockk<ChannelProjectionReader>()
    private val repoEventPublisher = mockk<ProjectGithubRepoEventPublisher>(relaxed = true)
    private val projectAccessGuard =
        ProjectAccessGuard(projectRepository, mockk<ProjectAccessProjectionRepository>(relaxed = true), teamMembershipRepository, mockk(relaxed = true))

    private val service = SetProjectGithubWebhookChannelServiceImpl(
        projectGithubRepoRepository,
        projectAccessGuard,
        channelProjectionReader,
        repoEventPublisher,
    )

    private fun project(id: Long = 1L, teamId: Long = 100L) =
        Project(id = id, teamId = teamId, name = "p", description = null, createdBy = 1L)

    private fun repoLink(id: Long = 5L, projectId: Long = 1L, teamId: Long = 100L) = ProjectGithubRepo(
        id = id,
        projectId = projectId,
        teamId = teamId,
        githubRepoUrl = "https://github.com/my-org/my-repo",
    )

    @Nested
    inner class Execute {
        @Test
        fun `setGithubWebhookChannel은 이 프로젝트 소속 채널이면 저장`() {
            val proj = project()
            every { projectRepository.findByIdForUpdate(1L) } returns proj
            every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 99L) } returns
                TeamMembership(teamId = 100L, userId = 99L, role = "OWNER")
            every { projectGithubRepoRepository.findByIdAndProjectId(5L, 1L) } returns repoLink()
            every { projectGithubRepoRepository.findByIdAndProjectIdForUpdate(5L, 1L) } returns repoLink()
            every { channelProjectionReader.requireProjectChannel(10L, 1L) } just runs
            every { projectGithubRepoRepository.save(any<ProjectGithubRepo>()) } answers { firstArg() }

            val response = service.execute(99L, 1L, 5L, SetProjectGithubWebhookChannelReqDto(channelId = 10L))

            assertEquals(10L, response.githubWebhookChannelId)
        }

        @Test
        fun `setGithubWebhookChannel은 다른 프로젝트 소속 채널이면 BAD_REQUEST`() {
            val proj = project()
            every { projectRepository.findByIdForUpdate(1L) } returns proj
            every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 99L) } returns
                TeamMembership(teamId = 100L, userId = 99L, role = "OWNER")
            every { projectGithubRepoRepository.findByIdAndProjectId(5L, 1L) } returns repoLink()
            every { projectGithubRepoRepository.findByIdAndProjectIdForUpdate(5L, 1L) } returns repoLink()
            every { channelProjectionReader.requireProjectChannel(10L, 1L) } throws
                ExpectedException("이 프로젝트 소속 채널이 아닙니다.", HttpStatus.BAD_REQUEST)

            val ex = assertThrows(ExpectedException::class.java) {
                service.execute(99L, 1L, 5L, SetProjectGithubWebhookChannelReqDto(channelId = 10L))
            }
            assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
        }

        @Test
        fun `setGithubWebhookChannel은 팀 멤버가 아니면 FORBIDDEN`() {
            val proj = project()
            every { projectRepository.findByIdForUpdate(1L) } returns proj

            every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 50L) } returns null

            val ex = assertThrows(ExpectedException::class.java) {
                service.execute(50L, 1L, 5L, SetProjectGithubWebhookChannelReqDto(channelId = 10L))
            }
            assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
        }

        @Test
        fun `setGithubWebhookChannel은 등록된 레포가 없으면 NOT_FOUND`() {
            val proj = project()
            every { projectRepository.findByIdForUpdate(1L) } returns proj
            every { teamMembershipRepository.findActiveByTeamIdAndUserId(100L, 99L) } returns
                TeamMembership(teamId = 100L, userId = 99L, role = "OWNER")
            every { projectGithubRepoRepository.findByIdAndProjectId(5L, 1L) } returns null

            val ex = assertThrows(ExpectedException::class.java) {
                service.execute(99L, 1L, 5L, SetProjectGithubWebhookChannelReqDto(channelId = 10L))
            }
            assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
        }
    }
}
