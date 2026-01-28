package com.cowork.meetingnote

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.meetingnote.domain.MeetingNoteTemplate
import com.cowork.channel.meetingnote.domain.SectionType
import com.cowork.channel.meetingnote.domain.TemplateSection
import com.cowork.channel.meetingnote.dto.CreateSectionRequest
import com.cowork.channel.meetingnote.dto.CreateTemplateRequest
import com.cowork.channel.meetingnote.repository.MeetingNoteTemplateRepository
import com.cowork.channel.meetingnote.repository.TemplateSectionRepository
import com.cowork.channel.meetingnote.service.MeetingNoteTemplateService
import com.cowork.channel.service.ChannelAccessChecker
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MeetingNoteTemplateServiceTest {

    private val templateRepository = mockk<MeetingNoteTemplateRepository>(relaxed = true)
    private val sectionRepository = mockk<TemplateSectionRepository>(relaxed = true)
    private val channelAccessChecker = mockk<ChannelAccessChecker>()
    private val service = MeetingNoteTemplateService(templateRepository, sectionRepository, channelAccessChecker)

    private val channel = Channel(
        teamId = 1, projectId = null, name = "회의록", viewType = ChannelViewType.MEETING_NOTE, description = null,
        isPrivate = false, position = 0, createdBy = 1, id = 30,
    )

    @BeforeEach
    fun allowRead() {
        every { channelAccessChecker.requireReadable(30, any()) } returns channel
        every { channelAccessChecker.requireViewType(channel, ChannelViewType.MEETING_NOTE) } just runs
        every { sectionRepository.findAllByTemplateIdOrderByPositionAscIdAsc(any()) } returns emptyList()
        every { sectionRepository.save(any()) } answers { firstArg() }
    }

    private fun template(id: Long, createdBy: Long = 1, active: Boolean = false) =
        MeetingNoteTemplate(channelId = 30, name = "주간 회의", createdBy = createdBy, isActive = active, id = id)

    @Test
    fun `채널의 첫 템플릿은 바로 활성화한다`() {
        val saved = slot<MeetingNoteTemplate>()
        every { templateRepository.findFirstByChannelIdAndIsActiveTrue(30) } returns null
        every { templateRepository.save(capture(saved)) } answers { saved.captured }

        service.create(30, 1, CreateTemplateRequest("주간 회의"))

        assertTrue(saved.captured.isActive)
    }

    @Test
    fun `섹션은 요청 순서대로 위치를 매긴다`() {
        every { templateRepository.findFirstByChannelIdAndIsActiveTrue(30) } returns template(1, active = true)
        every { templateRepository.save(any()) } answers { firstArg() }

        service.create(
            30, 1,
            CreateTemplateRequest(
                "회고",
                listOf(CreateSectionRequest("날짜", SectionType.DATE), CreateSectionRequest("내용", SectionType.MARKDOWN)),
            ),
        )

        verify { sectionRepository.save(match<TemplateSection> { it.title == "날짜" && it.position == 0 }) }
        verify { sectionRepository.save(match<TemplateSection> { it.title == "내용" && it.position == 1 }) }
    }
}
