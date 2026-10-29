package com.cowork.channel.domain.meetingNote.service.impl

import com.cowork.channel.domain.meetingNote.entity.MeetingNoteTemplate
import com.cowork.channel.domain.meetingNote.repository.MeetingNoteRepository
import com.cowork.channel.domain.meetingNote.repository.MeetingNoteTemplateRepository
import com.cowork.channel.domain.meetingNote.repository.TemplateSectionRepository
import com.cowork.channel.domain.meetingNote.service.MeetingNoteAccessGuard
import com.cowork.channel.domain.meetingNote.service.support.MeetingNoteTemplateLookupSupport
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.util.Optional

class DeleteMeetingNoteTemplateServiceImplTest {

    private val templateRepository = mockk<MeetingNoteTemplateRepository>(relaxed = true)
    private val meetingNoteRepository = mockk<MeetingNoteRepository>(relaxed = true)
    private val sectionRepository = mockk<TemplateSectionRepository>(relaxed = true)
    private val meetingNoteAccessGuard = mockk<MeetingNoteAccessGuard>(relaxed = true)
    private val lookupSupport = MeetingNoteTemplateLookupSupport(templateRepository, sectionRepository)

    private val service = DeleteMeetingNoteTemplateServiceImpl(
        templateRepository,
        meetingNoteRepository,
        meetingNoteAccessGuard,
        lookupSupport,
    )

    private fun template(
        id: Long = 10L,
        channelId: Long = 1L,
        name: String = "기본 템플릿",
        isActive: Boolean = false,
        createdBy: Long = 1L,
    ) = MeetingNoteTemplate(id = id, channelId = channelId, name = name, isActive = isActive, createdBy = createdBy)

    @Test
    fun `deleteTemplate은 활성 템플릿이면 BAD_REQUEST`() {
        every { meetingNoteAccessGuard.requireChannelAccessForUpdate(1L, 7L) } returns Unit
        every { templateRepository.findById(10L) } returns Optional.of(template(isActive = true))

        val ex = assertThrows(ExpectedException::class.java) {
            service.deleteTemplate(7L, 1L, 10L)
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
        verify(exactly = 0) { templateRepository.delete(any()) }
    }

    @Test
    fun `deleteTemplate은 회의록이 작성된 템플릿이면 BAD_REQUEST`() {
        every { meetingNoteAccessGuard.requireChannelAccess(1L, 7L) } returns Unit
        every { templateRepository.findById(10L) } returns Optional.of(template(isActive = false))
        every { meetingNoteRepository.existsByTemplateId(10L) } returns true

        val ex = assertThrows(ExpectedException::class.java) {
            service.deleteTemplate(7L, 1L, 10L)
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
        verify(exactly = 0) { templateRepository.delete(any()) }
    }

    @Test
    fun `deleteTemplate은 확인 후 회의록이 생겨 FK가 삭제를 막으면 BAD_REQUEST`() {
        every { meetingNoteAccessGuard.requireChannelAccess(1L, 7L) } returns Unit
        every { templateRepository.findById(10L) } returns Optional.of(template(isActive = false))
        every { meetingNoteRepository.existsByTemplateId(10L) } returns false
        every { templateRepository.flush() } throws DataIntegrityViolationException("fk_tb_meeting_notes_template")

        val ex = assertThrows(ExpectedException::class.java) {
            service.deleteTemplate(7L, 1L, 10L)
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `deleteTemplate은 회의록이 없는 비활성 템플릿을 삭제`() {
        val tmpl = template(isActive = false)
        every { meetingNoteAccessGuard.requireChannelAccessForUpdate(1L, 7L) } returns Unit
        every { templateRepository.findById(10L) } returns Optional.of(tmpl)
        every { meetingNoteRepository.existsByTemplateId(10L) } returns false

        service.deleteTemplate(7L, 1L, 10L)

        verifyOrder {
            meetingNoteAccessGuard.requireChannelAccessForUpdate(1L, 7L)
            templateRepository.findById(10L)
            templateRepository.delete(tmpl)
        }
    }
}
