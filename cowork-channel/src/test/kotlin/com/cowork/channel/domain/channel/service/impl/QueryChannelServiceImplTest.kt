package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.repository.DmParticipantRepository
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.support.ChannelPermissionSupport
import com.cowork.channel.domain.channelRolePolicy.service.ChannelMessageReadPolicyEvaluator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.util.Optional

class QueryChannelServiceImplTest {

    private val channelRepository = mockk<ChannelRepository>(relaxed = true)
    private val dmParticipantRepository = mockk<DmParticipantRepository>(relaxed = true)
    private val teamPermissionService = mockk<com.cowork.channel.domain.channel.service.TeamPermissionService>()
    private val evaluator = mockk<ChannelMessageReadPolicyEvaluator>(relaxed = true)
    private val channelAccessGuard = ChannelAccessGuard(channelRepository)
    private val channelPermissionSupport = ChannelPermissionSupport(
        dmParticipantRepository,
        teamPermissionService,
        evaluator,
    )

    private val service = QueryChannelServiceImpl(channelAccessGuard, channelPermissionSupport)

    private fun dmChannel(id: Long = 1L, createdBy: Long = 1L) = Channel(
        id = id, teamId = null, name = "DM", type = ChannelType.DM, viewType = ChannelViewType.TEXT,
        description = null, isPrivate = true, createdBy = createdBy, dmKey = "1:2",
    )

    private fun teamChannel(id: Long = 1L, isPrivate: Boolean) = Channel(
        id = id,
        teamId = 10L,
        name = "team",
        type = ChannelType.TEXT,
        viewType = ChannelViewType.TEXT,
        description = null,
        isPrivate = isPrivate,
        createdBy = 1L,
    )

    @Test
    fun `getChannel은 DM 채널이면 채널 멤버에게 허용`() {
        every { channelRepository.findById(1L) } returns Optional.of(dmChannel())
        every { dmParticipantRepository.existsByChannelIdAndUserId(1L, 2L) } returns true

        val res = service.execute(2L, 1L)
        assertEquals(null, res.teamId)
        assertEquals(ChannelType.DM.name, res.type)
    }

    @Test
    fun `getChannel은 DM 채널 비멤버이면 FORBIDDEN`() {
        every { channelRepository.findById(1L) } returns Optional.of(dmChannel())
        every { dmParticipantRepository.existsByChannelIdAndUserId(1L, 9L) } returns false

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(9L, 1L)
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    @Test
    fun `getChannel은 공개 팀 채널이면 팀 멤버에게 허용`() {
        every { channelRepository.findById(1L) } returns Optional.of(teamChannel(isPrivate = false))
        every { teamPermissionService.requireTeamMember(10L, 42L) } returns Unit

        val response = service.execute(42L, 1L)

        assertEquals(1L, response.id)
    }

    @Test
    fun `getChannel은 비공개 팀 채널이면 채널 멤버에게만 허용`() {
        every { channelRepository.findById(1L) } returns Optional.of(teamChannel(isPrivate = true))
        every { teamPermissionService.requireTeamMember(10L, 42L) } returns Unit
        every { dmParticipantRepository.existsByChannelIdAndUserId(1L, 42L) } returns true

        val response = service.execute(42L, 1L)

        assertEquals(1L, response.id)
    }

    @Test
    fun `getChannel은 비공개 팀 채널도 역할 권한으로 조회한다`() {
        every { channelRepository.findById(1L) } returns Optional.of(teamChannel(isPrivate = true))
        every { teamPermissionService.requireTeamMember(10L, 99L) } returns Unit
        service.execute(99L, 1L)
        verify(exactly = 0) { dmParticipantRepository.existsByChannelIdAndUserId(any(), any()) }
    }
}
