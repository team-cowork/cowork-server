package com.cowork.channel

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.repository.ChannelMemberRepository
import com.cowork.channel.repository.ChannelRepository
import com.cowork.channel.service.ChannelLifecycleListener
import com.cowork.channel.service.ChannelService
import com.cowork.project.event.ProjectDeletedEvent
import com.cowork.team.event.TeamDeletedEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class ChannelLifecycleListenerTest {

    private val channelRepository = mockk<ChannelRepository>(relaxed = true)
    private val channelMemberRepository = mockk<ChannelMemberRepository>(relaxed = true)
    private val channelService = mockk<ChannelService>(relaxed = true)
    private val listener = ChannelLifecycleListener(channelRepository, channelMemberRepository, channelService)

    private fun channel(id: Long) = Channel(
        teamId = 1, projectId = null, name = "ch$id", viewType = ChannelViewType.TEXT, description = null,
        isPrivate = false, position = 0, createdBy = 1, id = id,
    )

    @Test
    fun `팀을 삭제하면 팀의 채널을 하나씩 삭제한다`() {
        val channels = listOf(channel(1), channel(2))
        every { channelRepository.findAllByTeamIdOrderByPositionAscIdAsc(1) } returns channels

        listener.onTeamDeleted(TeamDeletedEvent(1))

        verify { channelService.deleteChannel(channels[0]) }
        verify { channelService.deleteChannel(channels[1]) }
    }

    @Test
    fun `프로젝트를 삭제하면 연결된 채널의 프로젝트만 해제한다`() {
        listener.onProjectDeleted(ProjectDeletedEvent(7, 1))

        verify { channelRepository.unassignProject(7) }
        verify(exactly = 0) { channelService.deleteChannel(any()) }
    }
}
