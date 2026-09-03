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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class SearchChannelsServiceImplTest {

    private val channelRepository = mockk<ChannelRepository>()
    private val teamPermissionService = mockk<TeamPermissionService>()
    private val evaluator = mockk<ChannelMessageReadPolicyEvaluator>()
    private val service = SearchChannelsServiceImpl(channelRepository, teamPermissionService, evaluator)

    private fun channel(id: Long, isPrivate: Boolean) = Channel(
        id = id,
        teamId = 10L,
        name = "secret",
        type = ChannelType.TEXT,
        viewType = ChannelViewType.TEXT,
        description = null,
        isPrivate = isPrivate,
        createdBy = 1L,
    )

    @Test
    fun `searchChannels는 팀 멤버에게 공개 및 가입한 비공개 채널만 반환함`() {
        every { teamPermissionService.requireTeamMember(10L, 42L) } returns Unit
        val channels = listOf(channel(1L, false), channel(2L, true))
        every { channelRepository.searchVisibleByTeamIdAndName(10L, 42L, "secret") } returns channels
        every { evaluator.filterReadable(10L, 42L, channels) } returns channels

        val result = service.execute(42L, 10L, "secret")

        assertEquals(listOf(1L, 2L), result.map { it.id })
        verify { channelRepository.searchVisibleByTeamIdAndName(10L, 42L, "secret") }
    }

    @Test
    fun `searchChannels는 팀 비멤버의 검색을 거부함`() {
        every { teamPermissionService.requireTeamMember(10L, 99L) } throws
            ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)

        val exception = assertThrows(ExpectedException::class.java) {
            service.execute(99L, 10L, "secret")
        }

        assertEquals(HttpStatus.FORBIDDEN, exception.statusCode)
        verify(exactly = 0) { channelRepository.searchVisibleByTeamIdAndName(any(), any(), any()) }
    }
}
