package com.cowork.channel.domain.meetingNote.service.impl

import com.cowork.channel.domain.channel.repository.ChannelMemberRepository
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.meetingNote.entity.MeetingNoteTemplate
import com.cowork.channel.domain.meetingNote.entity.SectionType
import com.cowork.channel.domain.meetingNote.entity.TemplateSection
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

class GetMeetingNoteTemplateServiceImplTest {

    private val templateRepository = mockk<MeetingNoteTemplateRepository>(relaxed = true)
    private val sectionRepository = mockk<TemplateSectionRepository>(relaxed = true)
    private val channelMemberRepository = mockk<ChannelMemberRepository>()
    private val channelRepository = mockk<ChannelRepository> {
        every { existsByIdAndType(any(), any()) } returns false
    }
    private val meetingNoteAccessGuard = MeetingNoteAccessGuard(channelMemberRepository, channelRepository)
    private val lookupSupport = MeetingNoteTemplateLookupSupport(templateRepository, sectionRepository)

    private val service = GetMeetingNoteTemplateServiceImpl(sectionRepository, meetingNoteAccessGuard, lookupSupport)

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
    fun `getTemplate은 섹션을 포함해서 반환`() {
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 7L) } returns true
        every { templateRepository.findById(10L) } returns Optional.of(template())
        every { sectionRepository.findAllByTemplateIdOrderByIdAsc(10L) } returns listOf(section())

        val result = service.getTemplate(7L, 1L, 10L)
        assertEquals(1, result.sections.size)
        assertEquals("회의 제목", result.sections[0].title)
    }

    @Test
    fun `getTemplate은 다른 채널 템플릿이면 NOT_FOUND`() {
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 7L) } returns true
        every { templateRepository.findById(10L) } returns Optional.of(template(channelId = 999L))

        val ex = assertThrows(ExpectedException::class.java) {
            service.getTemplate(7L, 1L, 10L)
        }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }

    @Test
    fun `getTemplate은 존재하지 않는 id이면 NOT_FOUND`() {
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 7L) } returns true
        every { templateRepository.findById(999L) } returns Optional.empty()

        val ex = assertThrows(ExpectedException::class.java) {
            service.getTemplate(7L, 1L, 999L)
        }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }
}
