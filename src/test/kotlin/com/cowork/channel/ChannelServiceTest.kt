package com.cowork.channel

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelMember
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.dto.CreateChannelRequest
import com.cowork.channel.repository.ChannelMemberRepository
import com.cowork.channel.repository.ChannelRepository
import com.cowork.channel.service.ChannelAccessChecker
import com.cowork.channel.service.ChannelService
import com.cowork.global.error.BusinessException
import com.cowork.project.domain.Project
import com.cowork.project.service.ProjectAccessChecker
import com.cowork.team.domain.TeamMember
import com.cowork.team.domain.TeamMemberRole
import com.cowork.team.domain.TeamPermission
import com.cowork.team.service.TeamAccessChecker
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus

class ChannelServiceTest {

    private val channelRepository = mockk<ChannelRepository>(relaxed = true)
    private val channelMemberRepository = mockk<ChannelMemberRepository>(relaxed = true)
    private val channelAccessChecker = mockk<ChannelAccessChecker>()
    private val teamAccessChecker = mockk<TeamAccessChecker>()
    private val projectAccessChecker = mockk<ProjectAccessChecker>()
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val service = ChannelService(
        channelRepository,
        channelMemberRepository,
        channelAccessChecker,
        teamAccessChecker,
        projectAccessChecker,
        eventPublisher,
    )

    private val manager = TeamMember(teamId = 1, userId = 2, role = TeamMemberRole.ADMIN)

    private fun channel(id: Long, isPrivate: Boolean = false, projectId: Long? = null) = Channel(
        teamId = 1, projectId = projectId, name = "ch$id", viewType = ChannelViewType.TEXT, description = null,
        isPrivate = isPrivate, position = id.toInt(), createdBy = 2, id = id,
    )

    private fun project(teamId: Long) =
        Project(teamId = teamId, name = "p", description = null, githubRepoUrl = null, position = 0, createdBy = 2, id = 7)

    @Test
    fun `비공개 채널을 만들면 만든 사람을 채널 멤버로 추가한다`() {
        val saved = slot<Channel>()
        every { teamAccessChecker.requirePermission(1, 2, TeamPermission.MANAGE_CHANNELS) } returns manager
        every { channelRepository.findMaxPosition(1) } returns 3
        every { channelRepository.save(capture(saved)) } answers { saved.captured }
        every { channelMemberRepository.save(any<ChannelMember>()) } answers { firstArg() }

        val response = service.create(2, CreateChannelRequest(teamId = 1, name = "기획", viewType = ChannelViewType.TEXT, isPrivate = true))

        assertEquals(4, response.position)
        verify { channelMemberRepository.save(match<ChannelMember> { it.userId == 2L }) }
    }

    @Test
    fun `다른 팀의 프로젝트에는 채널을 연결할 수 없다`() {
        every { teamAccessChecker.requirePermission(1, 2, TeamPermission.MANAGE_CHANNELS) } returns manager
        every { projectAccessChecker.findProject(7) } returns project(teamId = 9)

        val exception = assertThrows<BusinessException> {
            service.create(2, CreateChannelRequest(teamId = 1, projectId = 7, name = "기획", viewType = ChannelViewType.TEXT))
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
    }
}
