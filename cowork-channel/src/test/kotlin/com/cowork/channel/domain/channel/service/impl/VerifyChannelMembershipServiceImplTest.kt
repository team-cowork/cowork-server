package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.repository.ChannelMemberRepository
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.channel.service.support.ChannelPermissionSupport
import com.cowork.channel.domain.membership.entity.TeamMembership
import com.cowork.channel.domain.membership.repository.TeamMembershipRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.util.Optional

class VerifyChannelMembershipServiceImplTest {

    private val channelRepository = mockk<ChannelRepository>(relaxed = true)
    private val channelMemberRepository = mockk<ChannelMemberRepository>(relaxed = true)
    private val teamMembershipRepository = mockk<TeamMembershipRepository>(relaxed = true)
    private val teamPermissionService = TeamPermissionService(teamMembershipRepository)
    private val channelAccessGuard = ChannelAccessGuard(channelRepository)
    private val channelPermissionSupport = ChannelPermissionSupport(channelMemberRepository, teamPermissionService)

    private val service = VerifyChannelMembershipServiceImpl(channelAccessGuard, channelPermissionSupport)

    private fun teamChannel(id: Long = 1L, teamId: Long = 10L) = Channel(
        id = id,
        teamId = teamId,
        name = "voice",
        type = ChannelType.VOICE,
        viewType = ChannelViewType.TEXT,
        description = null,
        isPrivate = false,
        createdBy = 1L,
    )

    private fun dmChannel(id: Long = 1L) = Channel(
        id = id, teamId = null, name = "DM", type = ChannelType.DM, viewType = ChannelViewType.TEXT,
        description = null, isPrivate = true, createdBy = 1L, dmKey = "1:2",
    )

    @Test
    fun `팀 채널 멤버이면 teamId를 반환한다`() {
        every { channelRepository.findById(1L) } returns Optional.of(teamChannel())
        every { teamMembershipRepository.findByTeamIdAndUserId(10L, 2L) } returns
            TeamMembership(teamId = 10L, userId = 2L, role = "MEMBER")

        val res = service.execute(1L, 2L)

        assertEquals(10L, res.teamId)
    }

    @Test
    fun `팀 멤버가 아니면 FORBIDDEN`() {
        every { channelRepository.findById(1L) } returns Optional.of(teamChannel())
        every { teamMembershipRepository.findByTeamIdAndUserId(10L, 9L) } returns null

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(1L, 9L)
        }

        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    @Test
    fun `채널이 존재하지 않으면 NOT_FOUND`() {
        every { channelRepository.findById(1L) } returns Optional.empty()

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(1L, 2L)
        }

        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }

    @Test
    fun `DM 채널 멤버이면 teamId가 null이다`() {
        every { channelRepository.findById(1L) } returns Optional.of(dmChannel())
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 2L) } returns true

        val res = service.execute(1L, 2L)

        assertEquals(null, res.teamId)
    }
}
