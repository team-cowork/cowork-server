package com.cowork.channel.domain.meetingNote.service.impl

import com.cowork.channel.domain.channel.repository.ChannelMemberRepository
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.meetingNote.entity.MeetingNote
import com.cowork.channel.domain.meetingNote.presentation.data.request.UpdateMeetingNoteRequest
import com.cowork.channel.domain.meetingNote.repository.MeetingNoteRepository
import com.cowork.channel.domain.meetingNote.service.MeetingNoteAccessGuard
import com.cowork.channel.domain.meetingNote.service.support.MeetingNoteLookupSupport
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.util.Optional

class UpdateMeetingNoteServiceImplTest {

    private val meetingNoteRepository = mockk<MeetingNoteRepository>(relaxed = true)
    private val channelMemberRepository = mockk<ChannelMemberRepository>()
    private val channelRepository = mockk<ChannelRepository> {
        every { existsByIdAndType(any(), any()) } returns false
    }
    private val meetingNoteAccessGuard = MeetingNoteAccessGuard(channelMemberRepository, channelRepository)
    private val meetingNoteLookupSupport = MeetingNoteLookupSupport(meetingNoteRepository)

    private val service = UpdateMeetingNoteServiceImpl(meetingNoteAccessGuard, meetingNoteLookupSupport)

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
    fun `updateNote는 작성자이면 title과 content를 수정`() {
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 7L) } returns true
        every { meetingNoteRepository.findById(1L) } returns Optional.of(note())

        val request = UpdateMeetingNoteRequest(title = "수정된 제목", content = "{\"key\":\"value\"}")
        val result = service.updateNote(7L, 1L, 1L, request)

        assertEquals("수정된 제목", result.title)
        assertEquals("{\"key\":\"value\"}", result.content)
    }

    @Test
    fun `updateNote는 null 필드는 수정하지 않음`() {
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 7L) } returns true
        every { meetingNoteRepository.findById(1L) } returns Optional.of(note(title = "원래 제목"))

        val request = UpdateMeetingNoteRequest(title = null, content = "{\"updated\":true}")
        val result = service.updateNote(7L, 1L, 1L, request)

        assertEquals("원래 제목", result.title)
    }

    @Test
    fun `updateNote는 title이 공백이면 BAD_REQUEST`() {
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 7L) } returns true

        val ex = assertThrows(ExpectedException::class.java) {
            service.updateNote(7L, 1L, 1L, UpdateMeetingNoteRequest(title = "   ", content = null))
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `updateNote는 title이 200자 초과이면 BAD_REQUEST`() {
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 7L) } returns true

        val ex = assertThrows(ExpectedException::class.java) {
            service.updateNote(7L, 1L, 1L, UpdateMeetingNoteRequest(title = "a".repeat(201), content = null))
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `updateNote는 변경 사항이 없으면 note를 그대로 반환`() {
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 7L) } returns true
        every { meetingNoteRepository.findById(1L) } returns Optional.of(note(title = "주간 회의", content = "{}"))

        val request = UpdateMeetingNoteRequest(title = "주간 회의", content = "{}")
        val result = service.updateNote(7L, 1L, 1L, request)

        assertEquals("주간 회의", result.title)
        assertEquals("{}", result.content)
    }

    @Test
    fun `updateNote는 채널 비멤버이면 FORBIDDEN`() {
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 7L) } returns false

        val ex = assertThrows(ExpectedException::class.java) {
            service.updateNote(7L, 1L, 1L, UpdateMeetingNoteRequest(title = "x", content = null))
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    @Test
    fun `updateNote는 작성자가 아니면 FORBIDDEN`() {
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 99L) } returns true
        every { meetingNoteRepository.findById(1L) } returns Optional.of(note(createdBy = 7L))

        val ex = assertThrows(ExpectedException::class.java) {
            service.updateNote(99L, 1L, 1L, UpdateMeetingNoteRequest(title = "x", content = null))
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }

    @Test
    fun `updateNote는 다른 채널의 회의록이면 NOT_FOUND`() {
        every { channelMemberRepository.existsByChannelIdAndUserId(2L, 7L) } returns true
        every { meetingNoteRepository.findById(1L) } returns Optional.of(note(channelId = 1L))

        val ex = assertThrows(ExpectedException::class.java) {
            service.updateNote(7L, 2L, 1L, UpdateMeetingNoteRequest(title = "x", content = null))
        }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }
}
