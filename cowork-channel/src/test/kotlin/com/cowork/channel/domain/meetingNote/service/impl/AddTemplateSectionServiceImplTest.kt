package com.cowork.channel.domain.meetingNote.service.impl

import com.cowork.channel.domain.channel.repository.ChannelMemberRepository
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.meetingNote.entity.MeetingNoteTemplate
import com.cowork.channel.domain.meetingNote.entity.SectionType
import com.cowork.channel.domain.meetingNote.entity.TemplateSection
import com.cowork.channel.domain.meetingNote.presentation.data.request.CreateTemplateSectionRequest
import com.cowork.channel.domain.meetingNote.repository.MeetingNoteTemplateRepository
import com.cowork.channel.domain.meetingNote.repository.TemplateSectionRepository
import com.cowork.channel.domain.meetingNote.service.MeetingNoteAccessGuard
import com.cowork.channel.domain.meetingNote.service.support.MeetingNoteTemplateLookupSupport
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.util.Optional

class AddTemplateSectionServiceImplTest {

    private val templateRepository = mockk<MeetingNoteTemplateRepository>(relaxed = true)
    private val sectionRepository = mockk<TemplateSectionRepository>(relaxed = true)
    private val channelMemberRepository = mockk<ChannelMemberRepository>()
    private val channelRepository = mockk<ChannelRepository> {
        every { existsByIdAndType(any(), any()) } returns false
    }
    private val meetingNoteAccessGuard = MeetingNoteAccessGuard(channelMemberRepository, channelRepository)
    private val lookupSupport = MeetingNoteTemplateLookupSupport(templateRepository, sectionRepository)

    private val service = AddTemplateSectionServiceImpl(sectionRepository, meetingNoteAccessGuard, lookupSupport)

    private fun template(
        id: Long = 10L,
        channelId: Long = 1L,
        name: String = "기본 템플릿",
        isActive: Boolean = false,
        createdBy: Long = 1L,
    ) = MeetingNoteTemplate(id = id, channelId = channelId, name = name, isActive = isActive, createdBy = createdBy)

    @Test
    fun `addSection은 섹션 타입을 파싱해서 저장`() {
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 7L) } returns true
        every { templateRepository.findById(10L) } returns Optional.of(template())
        val saved = slot<TemplateSection>()
        every { sectionRepository.save(capture(saved)) } answers { saved.captured }

        val result = service.addSection(7L, 1L, 10L, CreateTemplateSectionRequest("비고", "MARKDOWN", isRequired = false))
        assertEquals("비고", result.title)
        assertEquals("MARKDOWN", result.type)
        assertEquals(SectionType.MARKDOWN, saved.captured.type)
    }

    @Test
    fun `addSection은 유효하지 않은 타입이면 BAD_REQUEST`() {
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 7L) } returns true
        every { templateRepository.findById(10L) } returns Optional.of(template())

        val ex = assertThrows(ExpectedException::class.java) {
            service.addSection(7L, 1L, 10L, CreateTemplateSectionRequest("x", "INVALID"))
        }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }
}
