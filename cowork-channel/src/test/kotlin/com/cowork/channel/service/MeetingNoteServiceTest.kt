package com.cowork.channel.service

import com.cowork.channel.domain.MeetingNote
import com.cowork.channel.dto.UpdateMeetingNoteRequest
import com.cowork.channel.repository.ChannelMemberRepository
import com.cowork.channel.repository.MeetingNoteRepository
import com.cowork.channel.repository.MeetingNoteTemplateRepository
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.util.Optional

class MeetingNoteServiceTest {

    private val meetingNoteRepository = mockk<MeetingNoteRepository>(relaxed = true)
    private val templateRepository = mockk<MeetingNoteTemplateRepository>(relaxed = true)
    private val channelMemberRepository = mockk<ChannelMemberRepository>()

    private val service = MeetingNoteService(
        meetingNoteRepository, templateRepository, channelMemberRepository
    )

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

    // ──────────────────────────── updateNote ────────────────────────────

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

    // ──────────────────────────── deleteNote ────────────────────────────

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
