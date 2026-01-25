package com.cowork.channel.meetingnote.service

import com.cowork.channel.event.ChannelDeletedEvent
import com.cowork.channel.meetingnote.domain.MeetingNote
import com.cowork.channel.meetingnote.dto.CreateMeetingNoteRequest
import com.cowork.channel.meetingnote.dto.MeetingNotePageResponse
import com.cowork.channel.meetingnote.dto.MeetingNoteResponse
import com.cowork.channel.meetingnote.dto.MeetingNoteSummaryResponse
import com.cowork.channel.meetingnote.repository.MeetingNoteRepository
import com.cowork.channel.meetingnote.repository.MeetingNoteTemplateRepository
import com.cowork.channel.meetingnote.repository.TemplateSectionRepository
import com.cowork.global.error.BusinessException
import com.cowork.team.repository.TeamMemberRepository
import org.springframework.context.event.EventListener
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class MeetingNoteService(
    private val meetingNoteRepository: MeetingNoteRepository,
    private val templateRepository: MeetingNoteTemplateRepository,
    private val sectionRepository: TemplateSectionRepository,
    private val teamMemberRepository: TeamMemberRepository,
    private val templateService: MeetingNoteTemplateService,
) {

    @Transactional
    fun getNotes(channelId: Long, userId: Long, before: Long?, limit: Int): MeetingNotePageResponse {
        templateService.requireNoteChannel(channelId, userId)
        val pageSize = limit.coerceIn(1, MAX_PAGE_SIZE)
        val notes = meetingNoteRepository.findPage(channelId, before, PageRequest.of(0, pageSize))
        return MeetingNotePageResponse(
            notes = notes.map(MeetingNoteSummaryResponse::from),
            nextCursor = if (notes.size == pageSize) notes.last().id else null,
        )
    }

    @Transactional
    fun getNote(channelId: Long, noteId: Long, userId: Long): MeetingNoteResponse {
        templateService.requireNoteChannel(channelId, userId)
        val note = meetingNoteRepository.findByIdAndChannelId(noteId, channelId)
            ?: throw BusinessException(HttpStatus.NOT_FOUND, "회의록을 찾을 수 없습니다.")
        return MeetingNoteResponse.from(note)
    }

    @Transactional
    fun create(channelId: Long, userId: Long, request: CreateMeetingNoteRequest): MeetingNoteResponse {
        val channel = templateService.requireNoteChannel(channelId, userId)
        val template = templateRepository.findFirstByChannelIdAndIsActiveTrue(channelId)
            ?: throw BusinessException(HttpStatus.BAD_REQUEST, "활성화된 회의록 템플릿이 없습니다.")
        val sections = sectionRepository.findAllByTemplateIdOrderByPositionAscIdAsc(template.id)
        val memberIds = teamMemberRepository.findUserIdsByTeamId(channel.teamId).toSet()
        val content = MeetingNoteContentValidator.validate(sections, request.values, memberIds)

        val note = meetingNoteRepository.save(
            MeetingNote(
                channelId = channelId,
                templateId = template.id,
                title = request.title,
                content = content,
                createdBy = userId,
            ),
        )
        return MeetingNoteResponse.from(note)
    }

    companion object {
        private const val MAX_PAGE_SIZE = 50
    }
}
