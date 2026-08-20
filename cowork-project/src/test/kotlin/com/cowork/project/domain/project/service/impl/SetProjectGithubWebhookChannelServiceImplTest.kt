package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.membership.repository.TeamMembershipRepository
import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.presentation.data.request.SetProjectGithubWebhookChannelReqDto
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.projectMember.entity.ProjectMember
import com.cowork.project.domain.projectMember.entity.ProjectMemberRole
import com.cowork.project.domain.projectMember.repository.ProjectMemberRepository
import com.cowork.project.global.client.ChannelClient
import com.cowork.project.global.client.ChannelResDto
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.util.Optional

class SetProjectGithubWebhookChannelServiceImplTest {

    private val projectRepository = mockk<ProjectRepository>(relaxed = true)
    private val projectMemberRepository = mockk<ProjectMemberRepository>(relaxed = true)
    private val teamMembershipRepository = mockk<TeamMembershipRepository>()
    private val channelClient = mockk<ChannelClient>()
    private val projectAccessGuard =
        ProjectAccessGuard(projectRepository, projectMemberRepository, teamMembershipRepository)

    private val service = SetProjectGithubWebhookChannelServiceImpl(
        projectMemberRepository,
        projectAccessGuard,
        channelClient,
    )

    private fun project(id: Long = 1L, teamId: Long = 100L) =
        Project(id = id, teamId = teamId, name = "p", description = null, createdBy = 1L)

    @Test
    fun `setGithubWebhookChannel은 이 프로젝트 소속 채널이면 저장`() {
        val proj = project()
        every { projectRepository.findById(1L) } returns Optional.of(proj)
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 99L) } returns
            ProjectMember(projectId = 1L, userId = 99L, role = ProjectMemberRole.OWNER)
        every { channelClient.getChannel(99L, 10L) } returns ChannelResDto(id = 10L, projectId = 1L)

        val response = service.execute(99L, 1L, SetProjectGithubWebhookChannelReqDto(channelId = 10L))

        assertEquals(10L, response.githubWebhookChannelId)
    }

    @Test
    fun `setGithubWebhookChannel은 다른 프로젝트 소속 채널이면 BAD_REQUEST`() {
        val proj = project()
        every { projectRepository.findById(1L) } returns Optional.of(proj)
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 99L) } returns
            ProjectMember(projectId = 1L, userId = 99L, role = ProjectMemberRole.OWNER)
        every { channelClient.getChannel(99L, 10L) } returns ChannelResDto(id = 10L, projectId = 2L)

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(99L, 1L, SetProjectGithubWebhookChannelReqDto(channelId = 10L))
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `setGithubWebhookChannel은 EDITOR가 아니면 FORBIDDEN`() {
        val proj = project()
        every { projectRepository.findById(1L) } returns Optional.of(proj)
        every { projectMemberRepository.findByProjectIdAndUserId(1L, 50L) } returns
            ProjectMember(projectId = 1L, userId = 50L, role = ProjectMemberRole.VIEWER)
        every { teamMembershipRepository.findByTeamIdAndUserId(100L, 50L) } returns null

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(50L, 1L, SetProjectGithubWebhookChannelReqDto(channelId = 10L))
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }
}
