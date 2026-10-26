package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.event.ChannelEventPublisher
import com.cowork.channel.domain.channel.presentation.data.request.CreateChannelRequest
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.channelRolePolicy.service.support.ChannelRolePolicyPermissionSchema
import com.cowork.channel.domain.meetingNote.service.CreateDefaultMeetingNoteTemplateService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class CreateChannelServiceImplTest {

    private val channelRepository = mockk<ChannelRepository>(relaxed = true)
    private val teamPermission = mockk<TeamPermissionService>()
    private val channelEventPublisher = mockk<ChannelEventPublisher>(relaxed = true)
    private val createDefaultMeetingNoteTemplateService = mockk<CreateDefaultMeetingNoteTemplateService>(relaxed = true)

    private val service = CreateChannelServiceImpl(
        channelRepository,
        teamPermission,
        channelEventPublisher,
        createDefaultMeetingNoteTemplateService,
        ChannelRolePolicyPermissionSchema(),
    )

    @Test
    fun `createChannel은 팀 비멤버이면 FORBIDDEN`() {
        every { teamPermission.requirePermission(100L, 7L, "manage_channels") } throws
            ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(
                7L,
                CreateChannelRequest(teamId = 100L, name = "n", type = "TEXT", viewType = "WEBHOOK"),
            )
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    @Test
    fun `createChannel은 type+viewType 모두 저장`() {
        every { teamPermission.requirePermission(any(), any(), "manage_channels") } returns Unit
        every { channelRepository.findAllByTeamIdForUpdateOrderByIdAsc(100L) } returns
            listOf(channel(id = 1L, position = 4), channel(id = 2L, position = 2))
        val saved = slot<Channel>()
        every { channelRepository.save(capture(saved)) } answers { saved.captured }

        service.execute(
            1L,
            CreateChannelRequest(
                teamId = 100L,
                name = "n",
                type = "TEXT",
                viewType = "MEETING_NOTE",
            ),
        )

        assertEquals(ChannelType.TEXT, saved.captured.type)
        assertEquals(ChannelViewType.MEETING_NOTE, saved.captured.viewType)
        assertEquals(5, saved.captured.position)
    }

    @Test
    fun `createChannel은 채널이 없는 팀에 position 0을 할당`() {
        every { teamPermission.requirePermission(any(), any(), "manage_channels") } returns Unit
        every { channelRepository.findAllByTeamIdForUpdateOrderByIdAsc(100L) } returns emptyList()
        val saved = slot<Channel>()
        every { channelRepository.save(capture(saved)) } answers { saved.captured }

        service.execute(1L, CreateChannelRequest(teamId = 100L, name = "n", type = "TEXT", viewType = "TEXT"))

        assertEquals(0, saved.captured.position)
    }

    @Test
    fun `createChannel은 동시 생성으로 position 유일성 제약이 충돌하면 CONFLICT`() {
        every { teamPermission.requirePermission(any(), any(), "manage_channels") } returns Unit
        every { channelRepository.findAllByTeamIdForUpdateOrderByIdAsc(100L) } returns emptyList()
        every { channelRepository.save(any()) } throws
            DataIntegrityViolationException("uq_tb_channels_team_id_position")

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(1L, CreateChannelRequest(teamId = 100L, name = "n", type = "TEXT", viewType = "TEXT"))
        }

        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
        verify(exactly = 0) { channelEventPublisher.publishCreated(any(), any(), any()) }
    }

    @Test
    fun `createChannel은 type=DM이면 BAD_REQUEST`() {
        every { teamPermission.requirePermission(any(), any(), "manage_channels") } returns Unit

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(1L, CreateChannelRequest(teamId = 100L, name = "n", type = "DM", viewType = "TEXT"))
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `생성자 접근을 보장하지 않는 비공개 요청은 채널을 저장하지 않는다`() {
        every { teamPermission.requirePermission(any(), any(), "manage_channels") } returns Unit
        every { teamPermission.prepareInitialRolePolicySeed(any(), 7L, emptyList()) } throws
            ExpectedException("생성자가 접근할 수 있도록 초기 채널 역할 정책을 지정해야 합니다.", HttpStatus.BAD_REQUEST)

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(
                7L,
                CreateChannelRequest(100L, "private", "TEXT", "TEXT", isPrivate = true),
            )
        }

        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
        verify(exactly = 0) { channelRepository.save(any()) }
    }

    private fun channel(id: Long, position: Int) = Channel(
        id = id,
        teamId = 100L,
        name = "ch",
        type = ChannelType.TEXT,
        viewType = ChannelViewType.TEXT,
        description = null,
        position = position,
        createdBy = 1L,
    )
}
