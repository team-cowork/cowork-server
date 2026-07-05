package com.cowork.channel.domain.meetingNote.service.impl

import com.cowork.channel.domain.channel.repository.ChannelMemberRepository
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.meetingNote.entity.MeetingNoteTemplate
import com.cowork.channel.domain.meetingNote.presentation.data.request.CreateMeetingNoteTemplateRequest
import com.cowork.channel.domain.meetingNote.repository.MeetingNoteTemplateRepository
import com.cowork.channel.domain.meetingNote.service.MeetingNoteAccessGuard
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CreateMeetingNoteTemplateServiceImplTest {

    private val templateRepository = mockk<MeetingNoteTemplateRepository>(relaxed = true)
    private val channelMemberRepository = mockk<ChannelMemberRepository>()
    private val channelRepository = mockk<ChannelRepository> {
        every { existsByIdAndType(any(), any()) } returns false
    }
    private val meetingNoteAccessGuard = MeetingNoteAccessGuard(channelMemberRepository, channelRepository)

    private val service = CreateMeetingNoteTemplateServiceImpl(templateRepository, meetingNoteAccessGuard)

    @Test
    fun `createTemplate은 isActive=false로 저장되고 sections는 빈 리스트`() {
        every { channelMemberRepository.existsByChannelIdAndUserId(1L, 7L) } returns true
        val saved = slot<MeetingNoteTemplate>()
        every { templateRepository.save(capture(saved)) } answers { saved.captured }

        val result = service.createTemplate(7L, 1L, CreateMeetingNoteTemplateRequest("스프린트 회의"))
        assertEquals("스프린트 회의", result.name)
        assertFalse(result.isActive)
        assertTrue(result.sections.isEmpty())
        assertFalse(saved.captured.isActive)
    }
}
