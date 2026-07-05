package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.repository.ChannelMemberRepository
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.support.ChannelPermissionSupport
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.util.Optional

class GetChannelServiceImplTest {

    private val channelRepository = mockk<ChannelRepository>(relaxed = true)
    private val channelMemberRepository = mockk<ChannelMemberRepository>(relaxed = true)
    private val channelAccessGuard = ChannelAccessGuard(channelRepository)
    private val channelPermissionSupport = ChannelPermissionSupport(channelMemberRepository, mockk(relaxed = true))

    private val service = GetChannelServiceImpl(channelAccessGuard, channelPermissionSupport)

    private fun dmChannel(id: Long = 1L, createdBy: Long = 1L) = Channel(
        id = id, teamId = null, name = "DM", type = ChannelType.DM, viewType = ChannelViewType.TEXT,
        description = null, isPrivate = true, createdBy = createdBy, dmKey = "1:2",
    )

    @Test
    fun `getChannel은 DM 채널이면 채널 멤버에게 허용`() {
        every { channelRepository.findById(1L) } returns Optional.of(dmChannel())
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 2L) } returns true

        val res = service.getChannel(2L, 1L)
        assertEquals(null, res.teamId)
        assertEquals(ChannelType.DM.name, res.type)
    }

    @Test
    fun `getChannel은 DM 채널 비멤버이면 FORBIDDEN`() {
        every { channelRepository.findById(1L) } returns Optional.of(dmChannel())
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 9L) } returns false

        val ex = assertThrows(ExpectedException::class.java) {
            service.getChannel(9L, 1L)
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }
}
