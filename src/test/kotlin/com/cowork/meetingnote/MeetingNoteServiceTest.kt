package com.cowork.meetingnote

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.meetingnote.dto.CreateMeetingNoteRequest
import com.cowork.channel.meetingnote.repository.MeetingNoteRepository
import com.cowork.channel.meetingnote.repository.MeetingNoteTemplateRepository
import com.cowork.channel.meetingnote.repository.TemplateSectionRepository
import com.cowork.channel.meetingnote.service.MeetingNoteService
import com.cowork.channel.meetingnote.service.MeetingNoteTemplateService
import com.cowork.global.error.BusinessException
import com.cowork.team.service.TeamAccessChecker
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus

class MeetingNoteServiceTest {

    private val noteRepository = mockk<MeetingNoteRepository>(relaxed = true)
    private val templateRepository = mockk<MeetingNoteTemplateRepository>(relaxed = true)
    private val sectionRepository = mockk<TemplateSectionRepository>(relaxed = true)
    private val teamAccessChecker = mockk<TeamAccessChecker>()
    private val templateService = mockk<MeetingNoteTemplateService>()
    private val service = MeetingNoteService(noteRepository, templateRepository, sectionRepository, teamAccessChecker, templateService)

    @BeforeEach
    fun allowRead() {
        every { templateService.requireNoteChannel(30, 1) } returns Channel(
            teamId = 1, projectId = null, name = "회의록", viewType = ChannelViewType.MEETING_NOTE, description = null,
            isPrivate = false, position = 0, createdBy = 1, id = 30,
        )
    }

    @Test
    fun `활성 템플릿이 없으면 회의록을 작성할 수 없다`() {
        every { templateRepository.findFirstByChannelIdAndIsActiveTrue(30) } returns null

        val exception = assertThrows<BusinessException> {
            service.create(30, 1, CreateMeetingNoteRequest(title = "주간 회의", values = emptyMap()))
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
    }

    @Test
    fun `없는 회의록은 404를 반환한다`() {
        every { noteRepository.findByIdAndChannelId(9, 30) } returns null

        val exception = assertThrows<BusinessException> { service.getNote(30, 9, 1) }

        assertEquals(HttpStatus.NOT_FOUND, exception.status)
    }
}
