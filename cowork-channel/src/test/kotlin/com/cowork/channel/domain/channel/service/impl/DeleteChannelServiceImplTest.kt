package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelType
import com.cowork.channel.domain.channel.entity.ChannelViewType
import com.cowork.channel.domain.channel.event.ChannelEventPublisher
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.repository.DmParticipantRepository
import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.channel.service.support.ChannelPermissionSupport
import com.cowork.channel.domain.meetingNote.repository.MeetingNoteRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.util.Optional

class DeleteChannelServiceImplTest {

    private val channelRepository = mockk<ChannelRepository>(relaxed = true)
    private val dmParticipantRepository = mockk<DmParticipantRepository>(relaxed = true)
    private val teamPermission = mockk<TeamPermissionService>()
    private val channelEventPublisher = mockk<ChannelEventPublisher>(relaxed = true)
    private val channelAccessGuard = ChannelAccessGuard(channelRepository)
    private val channelPermissionSupport = ChannelPermissionSupport(
        dmParticipantRepository,
        teamPermission,
        mockk(relaxed = true),
    )
    private val meetingNoteRepository = mockk<MeetingNoteRepository>(relaxed = true)

    private val service = DeleteChannelServiceImpl(
        channelRepository,
        channelEventPublisher,
        channelAccessGuard,
        channelPermissionSupport,
        meetingNoteRepository,
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

    @Test
    fun `deleteChannel은 채널을 지우기 전에 회의록을 먼저 삭제`() {
        val channel = Channel(
            id = 2L,
            teamId = 10L,
            name = "notes",
            type = ChannelType.TEXT,
            viewType = ChannelViewType.MEETING_NOTE,
            description = null,
            createdBy = 1L,
        )
        every { channelRepository.findById(2L) } returns Optional.of(channel)

        service.execute(1L, 2L)

        verifyOrder {
            meetingNoteRepository.deleteAllByChannelIdIn(listOf(2L))
            channelRepository.delete(channel)
        }
    }
}
