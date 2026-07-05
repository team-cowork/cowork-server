package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelMember
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.event.ChannelMemberEventPublisher
import com.cowork.channel.domain.channel.presentation.data.request.AddMemberRequest
import com.cowork.channel.domain.channel.repository.ChannelMemberRepository
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.channel.service.support.ChannelPermissionSupport
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.util.Optional

class AddChannelMemberServiceImplTest {

    private val channelRepository = mockk<ChannelRepository>(relaxed = true)
    private val channelMemberRepository = mockk<ChannelMemberRepository>(relaxed = true)
    private val teamPermission = mockk<TeamPermissionService>()
    private val channelMemberEventPublisher = mockk<ChannelMemberEventPublisher>(relaxed = true)
    private val channelAccessGuard = ChannelAccessGuard(channelRepository)
    private val channelPermissionSupport = ChannelPermissionSupport(channelMemberRepository, teamPermission)

    private val service = AddChannelMemberServiceImpl(
        channelMemberRepository,
        teamPermission,
        channelMemberEventPublisher,
        channelAccessGuard,
        channelPermissionSupport,
    )

    private fun channel(
        id: Long = 1L,
        teamId: Long = 100L,
        createdBy: Long = 1L,
        isPrivate: Boolean = false,
    ) = Channel(
        id = id, teamId = teamId, name = "ch", type = ChannelType.TEXT, viewType = ChannelViewType.TEXT,
        description = null, isPrivate = isPrivate, position = 0, createdBy = createdBy,
    )

    private fun dmChannel(id: Long = 1L, createdBy: Long = 1L) = Channel(
        id = id, teamId = null, name = "DM", type = ChannelType.DM, viewType = ChannelViewType.TEXT,
        description = null, isPrivate = true, createdBy = createdBy, dmKey = "1:2",
    )

    @Test
    fun `addMember는 비공개 채널일 때 채널 생성자만 추가 가능`() {
        val ch = channel(createdBy = 99L, isPrivate = true)
        every { channelRepository.findById(1L) } returns Optional.of(ch)
        every { teamPermission.isTeamOwnerOrAdmin(100L, 1L) } returns false

        val ex = assertThrows(ExpectedException::class.java) {
            service.addMember(1L, 1L, AddMemberRequest(userId = 50L))
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
        verify(exactly = 0) { channelMemberRepository.save(any()) }
    }

    @Test
    fun `addMember는 추가 대상이 팀 멤버 아니면 BAD_REQUEST`() {
        val ch = channel(createdBy = 1L)
        every { channelRepository.findById(1L) } returns Optional.of(ch)
        every { teamPermission.requireTeamMember(100L, 1L) } returns Unit
        every { teamPermission.isTeamMember(100L, 50L) } returns false

        val ex = assertThrows(ExpectedException::class.java) {
            service.addMember(1L, 1L, AddMemberRequest(userId = 50L))
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `addMember는 DM 채널이면 BAD_REQUEST`() {
        every { channelRepository.findById(1L) } returns Optional.of(dmChannel())

        val ex = assertThrows(ExpectedException::class.java) {
            service.addMember(1L, 1L, AddMemberRequest(userId = 50L))
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
        verify(exactly = 0) { channelMemberRepository.save(any()) }
    }

    @Test
    fun `addMember 정상 흐름`() {
        val ch = channel(createdBy = 1L)
        every { channelRepository.findById(1L) } returns Optional.of(ch)
        every { teamPermission.requireTeamMember(100L, 1L) } returns Unit
        every { teamPermission.isTeamMember(100L, 50L) } returns true
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 50L) } returns false
        val savedMember = slot<ChannelMember>()
        every { channelMemberRepository.save(capture(savedMember)) } answers { savedMember.captured }

        service.addMember(1L, 1L, AddMemberRequest(userId = 50L))
        assertEquals(50L, savedMember.captured.userId)
    }
}
