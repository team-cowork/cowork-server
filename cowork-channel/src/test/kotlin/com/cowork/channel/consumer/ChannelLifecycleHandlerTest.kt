package com.cowork.channel.consumer

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelType
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.repository.ChannelMemberRepository
import com.cowork.channel.repository.ChannelRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class ChannelLifecycleHandlerTest {

    private val channelRepository = mockk<ChannelRepository>(relaxed = true)
    private val channelMemberRepository = mockk<ChannelMemberRepository>(relaxed = true)

    private val handler = ChannelLifecycleHandler(channelRepository, channelMemberRepository)

    private fun channel(id: Long, teamId: Long, createdBy: Long) = Channel(
        id = id, teamId = teamId, name = "c$id", type = ChannelType.TEXT, viewType = ChannelViewType.TEXT,
        description = null, isPrivate = false, createdBy = createdBy,
    )

    @Test
    fun `onTeamDeleted는 팀의 모든 채널 삭제`() {
        val list = listOf(channel(1L, 100L, 1L), channel(2L, 100L, 2L))
        every { channelRepository.findAllByTeamIdOrderByIdAsc(100L) } returns list

        handler.onTeamDeleted(100L)

        verify(exactly = 1) { channelRepository.deleteAll(list) }
    }

    @Test
    fun `onMemberRemovedFromTeam은 생성자 채널만 삭제, 나머지는 멤버십만 제거`() {
        val ownedByTarget = channel(1L, 100L, 7L)
        val ownedByOther = channel(2L, 100L, 9L)
        every { channelRepository.findAllByTeamIdOrderByIdAsc(100L) } returns listOf(ownedByTarget, ownedByOther)

        handler.onMemberRemovedFromTeam(100L, 7L)

        verify(exactly = 1) { channelRepository.deleteAll(listOf(ownedByTarget)) }
        verify(exactly = 1) { channelMemberRepository.deleteAllByUserIdAndChannelIdIn(7L, listOf(2L)) }
    }
}
