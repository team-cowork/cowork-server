package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.event.ChannelEventPublisher
import com.cowork.channel.domain.channel.event.ChannelMembershipSyncPublisher
import com.cowork.channel.domain.channel.presentation.data.request.CreateChannelRequest
import com.cowork.channel.domain.channel.repository.ChannelMemberRepository
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.meetingNote.service.CreateDefaultMeetingNoteTemplateService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import team.themoment.sdk.exception.ExpectedException

class CreateChannelServiceImplTest {

    private val channelRepository = mockk<ChannelRepository>(relaxed = true)
    private val channelMemberRepository = mockk<ChannelMemberRepository>(relaxed = true)
    private val teamPermission = mockk<TeamPermissionService>()
    private val channelMembershipSyncPublisher = mockk<ChannelMembershipSyncPublisher>(relaxed = true)
    private val channelEventPublisher = mockk<ChannelEventPublisher>(relaxed = true)
    private val createDefaultMeetingNoteTemplateService = mockk<CreateDefaultMeetingNoteTemplateService>(relaxed = true)

    private val service = CreateChannelServiceImpl(
        channelRepository,
        channelMemberRepository,
        teamPermission,
        channelMembershipSyncPublisher,
        channelEventPublisher,
        createDefaultMeetingNoteTemplateService,
    )

    @BeforeEach
    fun setUp() {
        TransactionSynchronizationManager.initSynchronization()
    }

    @AfterEach
    fun tearDown() {
        TransactionSynchronizationManager.clear()
    }

    @Test
    fun `createChannel은 팀 비멤버이면 FORBIDDEN`() {
        every { teamPermission.requireTeamMember(100L, 7L) } throws
            ExpectedException("팀 멤버만 접근할 수 있습니다.", HttpStatus.FORBIDDEN)

        val ex = assertThrows(ExpectedException::class.java) {
            service.createChannel(
                7L,
                CreateChannelRequest(teamId = 100L, name = "n", type = "TEXT", viewType = "WEBHOOK"),
            )
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    @Test
    fun `createChannel은 type+viewType 모두 저장`() {
        every { teamPermission.requireTeamMember(any(), any()) } returns Unit
        every { channelRepository.findMaxPositionByTeamId(100L) } returns 4
        val saved = slot<Channel>()
        every { channelRepository.save(capture(saved)) } answers { saved.captured }
        every { channelMemberRepository.save(any()) } answers { firstArg() }

        service.createChannel(
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
        every { teamPermission.requireTeamMember(any(), any()) } returns Unit

        val ex = assertThrows(ExpectedException::class.java) {
            service.createChannel(1L, CreateChannelRequest(teamId = 100L, name = "n", type = "DM", viewType = "TEXT"))
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }
}
