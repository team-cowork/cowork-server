package com.cowork.channel

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.repository.ChannelMemberRepository
import com.cowork.channel.service.ChannelAudienceResolver
import com.cowork.team.service.TeamAccessChecker
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChannelAudienceResolverTest {

    private val channelMemberRepository = mockk<ChannelMemberRepository>()
    private val teamAccessChecker = mockk<TeamAccessChecker>()
    private val resolver = ChannelAudienceResolver(channelMemberRepository, teamAccessChecker)

    private fun channel(isPrivate: Boolean) = Channel(
        teamId = 1, projectId = null, name = "general", viewType = ChannelViewType.TEXT, description = null,
        isPrivate = isPrivate, position = 0, createdBy = 1, id = 10,
    )

    @Test
    fun `공개 채널은 팀 멤버 전체가 수신 대상이다`() {
        every { teamAccessChecker.findMemberIds(1) } returns setOf(1, 2, 3)

        assertEquals(setOf(1L, 2L, 3L), resolver.resolve(channel(isPrivate = false)))
        verify(exactly = 0) { channelMemberRepository.findUserIdsByChannelId(any()) }
    }

    @Test
    fun `비공개 채널은 채널 멤버만 수신 대상이다`() {
        every { channelMemberRepository.findUserIdsByChannelId(10) } returns listOf(2, 3)

        assertEquals(setOf(2L, 3L), resolver.resolve(channel(isPrivate = true)))
    }
}
