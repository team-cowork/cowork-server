package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.event.ChannelEventPublisher
import com.cowork.channel.domain.channel.event.ChannelMemberEventPublisher
import com.cowork.channel.domain.channel.repository.ChannelMemberRepository
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.channel.service.support.ChannelPermissionSupport
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.util.Optional

class DeleteChannelServiceImplTest {

    private val channelRepository = mockk<ChannelRepository>(relaxed = true)
    private val channelMemberRepository = mockk<ChannelMemberRepository>(relaxed = true)
    private val teamPermission = mockk<TeamPermissionService>()
    private val channelMemberEventPublisher = mockk<ChannelMemberEventPublisher>(relaxed = true)
    private val channelEventPublisher = mockk<ChannelEventPublisher>(relaxed = true)
    private val channelAccessGuard = ChannelAccessGuard(channelRepository)
    private val channelPermissionSupport = ChannelPermissionSupport(
        channelMemberRepository,
        teamPermission,
        mockk(relaxed = true),
    )

    private val service = DeleteChannelServiceImpl(
        channelRepository,
        channelMemberRepository,
        channelMemberEventPublisher,
        channelEventPublisher,
        channelAccessGuard,
        channelPermissionSupport,
    )

    private fun dmChannel(id: Long = 1L, createdBy: Long = 1L) = Channel(
        id = id, teamId = null, name = "DM", type = ChannelType.DM, viewType = ChannelViewType.TEXT,
        description = null, isPrivate = true, createdBy = createdBy, dmKey = "1:2",
    )

    @Test
    fun `deleteChannel은 DM 채널이면 BAD_REQUEST`() {
        every { channelRepository.findById(1L) } returns Optional.of(dmChannel())

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(1L, 1L)
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }
}
