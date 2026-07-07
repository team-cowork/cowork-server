package com.cowork.channel.domain.meetingNote.service.impl

import com.cowork.channel.domain.channel.repository.ChannelMemberRepository
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.meetingNote.entity.MeetingNoteTemplate
import com.cowork.channel.domain.meetingNote.entity.SectionType
import com.cowork.channel.domain.meetingNote.entity.TemplateSection
import com.cowork.channel.domain.meetingNote.presentation.data.request.UpdateTemplateSectionRequest
import com.cowork.channel.domain.meetingNote.repository.MeetingNoteTemplateRepository
import com.cowork.channel.domain.meetingNote.repository.TemplateSectionRepository
import com.cowork.channel.domain.meetingNote.service.MeetingNoteAccessGuard
import com.cowork.channel.domain.meetingNote.service.support.MeetingNoteTemplateLookupSupport
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.util.Optional

class UpdateTemplateSectionServiceImplTest {

    private val templateRepository = mockk<MeetingNoteTemplateRepository>(relaxed = true)
    private val sectionRepository = mockk<TemplateSectionRepository>(relaxed = true)
    private val channelMemberRepository = mockk<ChannelMemberRepository>()
    private val channelRepository = mockk<ChannelRepository> {
        every { existsByIdAndType(any(), any()) } returns false
    }
    private val meetingNoteAccessGuard = MeetingNoteAccessGuard(channelMemberRepository, channelRepository)
    private val lookupSupport = MeetingNoteTemplateLookupSupport(templateRepository, sectionRepository)

    private val service = UpdateTemplateSectionServiceImpl(meetingNoteAccessGuard, lookupSupport)

    private fun template(
        id: Long = 10L,
        channelId: Long = 1L,
        name: String = "기본 템플릿",
        isActive: Boolean = false,
        createdBy: Long = 1L,
    ) = MeetingNoteTemplate(id = id, channelId = channelId, name = name, isActive = isActive, createdBy = createdBy)

    private fun section(
        id: Long = 20L,
        templateId: Long = 10L,
        title: String = "회의 제목",
        type: SectionType = SectionType.TEXT,
    ) = TemplateSection(id = id, templateId = templateId, title = title, type = type)

    @Test
    fun `updateSection은 전달된 필드만 수정`() {
        val sec = section(type = SectionType.TEXT)
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 7L) } returns true
        every { templateRepository.findById(10L) } returns Optional.of(template())
        every { sectionRepository.findById(20L) } returns Optional.of(sec)

        val result = service.updateSection(7L, 1L, 10L, 20L, UpdateTemplateSectionRequest(title = "수정된 제목"))
        assertEquals("수정된 제목", result.title)
        assertEquals("TEXT", result.type)
    }

    @Test
    fun `updateSection은 다른 템플릿의 섹션이면 NOT_FOUND`() {
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 7L) } returns true
        every { templateRepository.findById(10L) } returns Optional.of(template(id = 10L))
        every { sectionRepository.findById(20L) } returns Optional.of(section(templateId = 999L))

        val ex = assertThrows(ExpectedException::class.java) {
            service.updateSection(7L, 1L, 10L, 20L, UpdateTemplateSectionRequest(title = "x"))
        }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }
}
