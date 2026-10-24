package com.cowork.channel.domain.meetingNote.service.impl

import com.cowork.channel.domain.meetingNote.entity.MeetingNoteTemplate
import com.cowork.channel.domain.meetingNote.repository.MeetingNoteTemplateRepository
import com.cowork.channel.domain.meetingNote.repository.TemplateSectionRepository
import com.cowork.channel.domain.meetingNote.service.MeetingNoteAccessGuard
import com.cowork.channel.domain.meetingNote.service.support.MeetingNoteTemplateLookupSupport
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional

class ActivateMeetingNoteTemplateServiceImplTest {

    private val templateRepository = mockk<MeetingNoteTemplateRepository>(relaxed = true)
    private val sectionRepository = mockk<TemplateSectionRepository>(relaxed = true)
    private val meetingNoteAccessGuard = mockk<MeetingNoteAccessGuard>(relaxed = true)
    private val lookupSupport = MeetingNoteTemplateLookupSupport(templateRepository, sectionRepository)

    private val service =
        ActivateMeetingNoteTemplateServiceImpl(
            templateRepository,
            sectionRepository,
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
    fun `activateTemplate은 기존 활성 템플릿을 비활성화하고 대상을 활성화`() {
        val current = template(id = 5L, isActive = true)
        val next = template(id = 10L, isActive = false)
        every { meetingNoteAccessGuard.requireChannelAccess(1L, 7L) } returns Unit
        every { templateRepository.findById(10L) } returns Optional.of(next)
        every { templateRepository.findByChannelIdAndIsActiveTrue(1L) } returns current
        every { sectionRepository.findAllByTemplateIdOrderByIdAsc(10L) } returns emptyList()

        service.activateTemplate(7L, 1L, 10L)

        assertFalse(current.isActive)
        assertTrue(next.isActive)
    }

    @Test
    fun `activateTemplate은 이미 활성 상태인 템플릿을 다시 활성화해도 오류 없음`() {
        val tmpl = template(id = 10L, isActive = true)
        every { meetingNoteAccessGuard.requireChannelAccess(1L, 7L) } returns Unit
        every { templateRepository.findById(10L) } returns Optional.of(tmpl)
        every { templateRepository.findByChannelIdAndIsActiveTrue(1L) } returns tmpl
        every { sectionRepository.findAllByTemplateIdOrderByIdAsc(10L) } returns emptyList()

        service.activateTemplate(7L, 1L, 10L)
        assertTrue(tmpl.isActive)
    }

    @Test
    fun `activateTemplate은 기존 활성 템플릿이 없어도 정상 동작`() {
        val next = template(id = 10L, isActive = false)
        every { meetingNoteAccessGuard.requireChannelAccess(1L, 7L) } returns Unit
        every { templateRepository.findById(10L) } returns Optional.of(next)
        every { templateRepository.findByChannelIdAndIsActiveTrue(1L) } returns null
        every { sectionRepository.findAllByTemplateIdOrderByIdAsc(10L) } returns emptyList()

        service.activateTemplate(7L, 1L, 10L)
        assertTrue(next.isActive)
    }
}
