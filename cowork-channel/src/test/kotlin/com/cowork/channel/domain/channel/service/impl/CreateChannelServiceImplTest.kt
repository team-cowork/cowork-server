package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.event.ChannelEventPublisher
import com.cowork.channel.domain.channel.presentation.data.request.CreateChannelRequest
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.meetingNote.service.CreateDefaultMeetingNoteTemplateService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
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
        every { channelRepository.findMaxPositionByTeamId(100L) } returns 4
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
    fun `createChannel은 type=DM이면 BAD_REQUEST`() {
        every { teamPermission.requirePermission(any(), any(), "manage_channels") } returns Unit

        val ex = assertThrows(ExpectedException::class.java) {
            service.execute(1L, CreateChannelRequest(teamId = 100L, name = "n", type = "DM", viewType = "TEXT"))
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }
}
