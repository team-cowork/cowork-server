package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.channelRolePolicy.service.ChannelMessageReadPolicyEvaluator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ListTeamChannelsServiceImplTest {

    private val channelRepository = mockk<ChannelRepository>()
    private val teamPermissionService = mockk<TeamPermissionService>()
    private val evaluator = mockk<ChannelMessageReadPolicyEvaluator>()
    private val service = ListTeamChannelsServiceImpl(channelRepository, teamPermissionService, evaluator)

    @Test
    fun `listTeamChannels는 공개 및 가입한 비공개 채널만 반환함`() {
        val channel = Channel(
            id = 1L,
            teamId = 10L,
            name = "general",
            type = ChannelType.TEXT,
            viewType = ChannelViewType.TEXT,
            description = null,
            isPrivate = false,
            createdBy = 1L,
        )
        every { teamPermissionService.requireTeamMember(10L, 42L) } returns Unit
        every { channelRepository.findVisibleByTeamIdOrderByPositionAscIdAsc(10L, 42L) } returns listOf(channel)
        every { evaluator.filterReadable(10L, 42L, listOf(channel)) } returns listOf(channel)

        val result = service.execute(42L, 10L)

        assertEquals(listOf(1L), result.map { it.id })
        verify { channelRepository.findVisibleByTeamIdOrderByPositionAscIdAsc(10L, 42L) }
    }
}
