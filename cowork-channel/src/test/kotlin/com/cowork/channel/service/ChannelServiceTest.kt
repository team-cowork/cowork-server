package com.cowork.channel.service

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelMember
import com.cowork.channel.domain.ChannelType
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.dto.AddMemberRequest
import com.cowork.channel.dto.CreateChannelRequest
import com.cowork.channel.dto.UpdateChannelRequest
import com.cowork.channel.repository.ChannelMemberRepository
import com.cowork.channel.repository.ChannelRepository
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

class ChannelServiceTest {

    private val channelRepository = mockk<ChannelRepository>(relaxed = true)
    private val channelMemberRepository = mockk<ChannelMemberRepository>(relaxed = true)
    private val teamPermission = mockk<TeamPermissionService>()

    private val service = ChannelService(channelRepository, channelMemberRepository, teamPermission)

    private fun channel(
        id: Long = 1L,
        teamId: Long = 100L,
        type: ChannelType = ChannelType.TEXT,
        view: ChannelViewType = ChannelViewType.TEXT,
        createdBy: Long = 1L,
        isPrivate: Boolean = false,
    ) = Channel(
        id = id, teamId = teamId, name = "ch", type = type, viewType = view,
        description = null, isPrivate = isPrivate, createdBy = createdBy,
    )

    @Test
    fun `createChannel은 팀 비멤버이면 FORBIDDEN`() {
        every { teamPermission.requireTeamMember(100L, 7L) } throws
            ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)

        val ex = assertThrows(ExpectedException::class.java) {
            service.createChannel(7L, CreateChannelRequest(teamId = 100L, name = "n", type = "TEXT", viewType = "WEBHOOK"))
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    @Test
    fun `createChannel은 type+viewType 모두 저장`() {
        every { teamPermission.requireTeamMember(any(), any()) } returns Unit
        val saved = slot<Channel>()
        every { channelRepository.save(capture(saved)) } answers { saved.captured }
        every { channelMemberRepository.save(any()) } answers { firstArg() }

        service.createChannel(1L, CreateChannelRequest(
            teamId = 100L, name = "n", type = "TEXT", viewType = "MEETING_NOTE",
        ))

        assertEquals(ChannelType.TEXT, saved.captured.type)
        assertEquals(ChannelViewType.MEETING_NOTE, saved.captured.viewType)
    }

    @Test
    fun `updateChannel은 팀 OWNER 등가 권한으로 통과`() {
        val ch = channel(createdBy = 99L)
        every { channelRepository.findById(1L) } returns Optional.of(ch)
        every { teamPermission.isTeamOwnerOrAdmin(100L, 1L) } returns true

        val res = service.updateChannel(1L, 1L, UpdateChannelRequest(name = "newName"))
        assertEquals("newName", res.name)
    }

    @Test
    fun `updateChannel은 비생성자 + 팀 일반멤버이면 FORBIDDEN`() {
        val ch = channel(createdBy = 99L)
        every { channelRepository.findById(1L) } returns Optional.of(ch)
        every { teamPermission.isTeamOwnerOrAdmin(100L, 1L) } returns false

        val ex = assertThrows(ExpectedException::class.java) {
            service.updateChannel(1L, 1L, UpdateChannelRequest(name = "x"))
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

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
