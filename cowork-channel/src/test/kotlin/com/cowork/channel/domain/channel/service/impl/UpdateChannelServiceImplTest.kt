package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.event.ChannelEventPublisher
import com.cowork.channel.domain.channel.presentation.data.request.UpdateChannelRequest
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

class UpdateChannelServiceImplTest {

    private val channelRepository = mockk<ChannelRepository>(relaxed = true)
    private val channelMemberRepository = mockk<ChannelMemberRepository>(relaxed = true)
    private val teamPermission = mockk<TeamPermissionService>()
    private val channelEventPublisher = mockk<ChannelEventPublisher>(relaxed = true)
    private val channelAccessGuard = ChannelAccessGuard(channelRepository)
    private val channelPermissionSupport = ChannelPermissionSupport(channelMemberRepository, teamPermission)

    private val service = UpdateChannelServiceImpl(channelEventPublisher, channelAccessGuard, channelPermissionSupport)

    private fun channel(
        id: Long = 1L,
        teamId: Long = 100L,
        type: ChannelType = ChannelType.TEXT,
        view: ChannelViewType = ChannelViewType.TEXT,
        createdBy: Long = 1L,
        isPrivate: Boolean = false,
        position: Int = 0,
        projectId: Long? = null,
    ) = Channel(
        id = id, teamId = teamId, name = "ch", type = type, viewType = view,
        description = null, isPrivate = isPrivate, position = position, createdBy = createdBy, projectId = projectId,
    )

    private fun dmChannel(id: Long = 1L, createdBy: Long = 1L) = Channel(
        id = id, teamId = null, name = "DM", type = ChannelType.DM, viewType = ChannelViewType.TEXT,
        description = null, isPrivate = true, createdBy = createdBy, dmKey = "1:2",
    )

    @Test
    fun `updateChannel은 팀 OWNER 등가 권한으로 통과`() {
        val ch = channel(createdBy = 99L)
        every { channelRepository.findById(1L) } returns Optional.of(ch)
        every { teamPermission.isTeamOwnerOrAdmin(100L, 1L) } returns true

        val res = service.updateChannel(1L, 1L, UpdateChannelRequest(name = "newName"), false)
        assertEquals("newName", res.name)
    }

    @Test
    fun `updateChannel은 비생성자 + 팀 일반멤버이면 FORBIDDEN`() {
        val ch = channel(createdBy = 99L)
        every { channelRepository.findById(1L) } returns Optional.of(ch)
        every { teamPermission.isTeamOwnerOrAdmin(100L, 1L) } returns false

        val ex = assertThrows(ExpectedException::class.java) {
            service.updateChannel(1L, 1L, UpdateChannelRequest(name = "x"), false)
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    @Test
    fun `updateChannel은 updateProjectId=true이면 projectId를 할당함`() {
        val ch = channel(createdBy = 1L)
        every { channelRepository.findById(1L) } returns Optional.of(ch)

        val res = service.updateChannel(1L, 1L, UpdateChannelRequest(projectId = 5L), true)
        assertEquals(5L, res.projectId)
    }

    @Test
    fun `updateChannel은 updateProjectId=false이면 기존 projectId를 유지함`() {
        val ch = channel(createdBy = 1L, projectId = 99L)
        every { channelRepository.findById(1L) } returns Optional.of(ch)

        val res = service.updateChannel(1L, 1L, UpdateChannelRequest(), false)
        assertEquals(99L, res.projectId)
    }

    @Test
    fun `updateChannel은 updateProjectId=true에 projectId=null이면 프로젝트 귀속 해제`() {
        val ch = channel(createdBy = 1L, projectId = 99L)
        every { channelRepository.findById(1L) } returns Optional.of(ch)

        val res = service.updateChannel(1L, 1L, UpdateChannelRequest(projectId = null), true)
        assertEquals(null, res.projectId)
    }

    @Test
    fun `updateChannel은 DM 채널이면 BAD_REQUEST`() {
        every { channelRepository.findById(1L) } returns Optional.of(dmChannel())

        val ex = assertThrows(ExpectedException::class.java) {
            service.updateChannel(1L, 1L, UpdateChannelRequest(name = "x"), false)
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }
}
