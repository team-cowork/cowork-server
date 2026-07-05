package com.cowork.channel.domain.meetingNote.service.impl

import com.cowork.channel.domain.channel.repository.ChannelMemberRepository
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.meetingNote.entity.MeetingNote
import com.cowork.channel.domain.meetingNote.repository.MeetingNoteRepository
import com.cowork.channel.domain.meetingNote.service.MeetingNoteAccessGuard
import com.cowork.channel.domain.meetingNote.service.support.MeetingNoteLookupSupport
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.util.Optional

class DeleteMeetingNoteServiceImplTest {

    private val meetingNoteRepository = mockk<MeetingNoteRepository>(relaxed = true)
    private val channelMemberRepository = mockk<ChannelMemberRepository>()
    private val channelRepository = mockk<ChannelRepository> {
        every { existsByIdAndType(any(), any()) } returns false
    }
    private val meetingNoteAccessGuard = MeetingNoteAccessGuard(channelMemberRepository, channelRepository)
    private val meetingNoteLookupSupport = MeetingNoteLookupSupport(meetingNoteRepository)

    private val service =
        DeleteMeetingNoteServiceImpl(meetingNoteRepository, meetingNoteAccessGuard, meetingNoteLookupSupport)

    private fun note(
        id: Long = 1L,
        channelId: Long = 1L,
        templateId: Long = 10L,
        title: String = "주간 회의",
        content: String = "{}",
        createdBy: Long = 7L,
    ) = MeetingNote(
        id = id,
        channelId = channelId,
        templateId = templateId,
        title = title,
        content = content,
        createdBy = createdBy,
    )

    @Test
    fun `deleteNote는 작성자이면 삭제`() {
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 7L) } returns true
        val n = note()
        every { meetingNoteRepository.findById(1L) } returns Optional.of(n)

        service.deleteNote(7L, 1L, 1L)

        verify { meetingNoteRepository.delete(n) }
    }

    @Test
    fun `deleteNote는 채널 비멤버이면 FORBIDDEN`() {
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 7L) } returns false

        val ex = assertThrows(ExpectedException::class.java) {
            service.deleteNote(7L, 1L, 1L)
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    @Test
    fun `deleteNote는 작성자가 아니면 FORBIDDEN`() {
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 99L) } returns true
        every { meetingNoteRepository.findById(1L) } returns Optional.of(note(createdBy = 7L))

        val ex = assertThrows(ExpectedException::class.java) {
            service.deleteNote(99L, 1L, 1L)
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    @Test
    fun `deleteNote는 다른 채널의 회의록이면 NOT_FOUND`() {
        every { channelMemberRepository.existsByChannelIdAndUserId(2L, 7L) } returns true
        every { meetingNoteRepository.findById(1L) } returns Optional.of(note(channelId = 1L))

        val ex = assertThrows(ExpectedException::class.java) {
            service.deleteNote(7L, 2L, 1L)
        }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }
}
