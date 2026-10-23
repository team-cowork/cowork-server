package com.cowork.channel.domain.meetingNote.service.impl

import com.cowork.channel.domain.meetingNote.entity.MeetingNoteTemplate
import com.cowork.channel.domain.meetingNote.repository.MeetingNoteTemplateRepository
import com.cowork.channel.domain.meetingNote.repository.TemplateSectionRepository
import com.cowork.channel.domain.meetingNote.service.MeetingNoteAccessGuard
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class ListMeetingNoteTemplatesServiceImplTest {

    private val templateRepository = mockk<MeetingNoteTemplateRepository>(relaxed = true)
    private val sectionRepository = mockk<TemplateSectionRepository>(relaxed = true)
    private val meetingNoteAccessGuard = mockk<MeetingNoteAccessGuard>(relaxed = true)

    private val service =
        ListMeetingNoteTemplatesServiceImpl(templateRepository, sectionRepository, meetingNoteAccessGuard)

    private fun template(
        id: Long = 10L,
        channelId: Long = 1L,
        name: String = "기본 템플릿",
        isActive: Boolean = false,
        createdBy: Long = 1L,
    ) = MeetingNoteTemplate(id = id, channelId = channelId, name = name, isActive = isActive, createdBy = createdBy)

    @Test
    fun `listTemplates는 채널 멤버이면 목록 반환`() {
        every { meetingNoteAccessGuard.requireChannelAccess(1L, 7L) } returns Unit
        every { templateRepository.findAllByChannelIdOrderByIdAsc(1L) } returns listOf(template())
        every { sectionRepository.findAllByTemplateIdInOrderByIdAsc(listOf(10L)) } returns emptyList()

        val result = service.listTemplates(7L, 1L)
        assertEquals(1, result.size)
        assertEquals(10L, result[0].id)
    }

    @Test
    fun `listTemplates는 채널 읽기 권한이 없으면이면 FORBIDDEN`() {
        every { meetingNoteAccessGuard.requireChannelAccess(1L, 7L) } throws
            ExpectedException("채널 접근 권한이 없습니다.", HttpStatus.FORBIDDEN)

        val ex = assertThrows(ExpectedException::class.java) {
            service.listTemplates(7L, 1L)
        }
        assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
    }
}
