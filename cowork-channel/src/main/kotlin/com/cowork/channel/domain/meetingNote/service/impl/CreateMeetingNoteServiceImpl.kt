package com.cowork.channel.domain.meetingNote.service.impl

import com.cowork.channel.domain.meetingNote.entity.MeetingNote
import com.cowork.channel.domain.meetingNote.presentation.data.request.CreateMeetingNoteRequest
import com.cowork.channel.domain.meetingNote.presentation.data.response.MeetingNoteResponse
import com.cowork.channel.domain.meetingNote.repository.MeetingNoteRepository
import com.cowork.channel.domain.meetingNote.repository.MeetingNoteTemplateRepository
import com.cowork.channel.domain.meetingNote.service.CreateMeetingNoteService
import com.cowork.channel.domain.meetingNote.service.MeetingNoteAccessGuard
import com.cowork.channel.domain.meetingNote.service.support.MeetingNoteContentSupport
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
@Transactional
class CreateMeetingNoteServiceImpl(
    private val meetingNoteRepository: MeetingNoteRepository,
    private val templateRepository: MeetingNoteTemplateRepository,
    private val meetingNoteAccessGuard: MeetingNoteAccessGuard,
    private val meetingNoteContentSupport: MeetingNoteContentSupport,
) : CreateMeetingNoteService {

    override fun createNote(userId: Long, channelId: Long, request: CreateMeetingNoteRequest): MeetingNoteResponse {
        meetingNoteAccessGuard.requireChannelAccess(channelId, userId)
        // 제목 길이 초과도 DataIntegrityViolationException이 되므로, 아래 FK 위반 처리와 섞이지 않도록 먼저 검사한다.
        if (request.title.isBlank()) throw ExpectedException("회의록 제목은 공백일 수 없습니다.", HttpStatus.BAD_REQUEST)
        if (request.title.length > 200) throw ExpectedException("회의록 제목은 200자를 초과할 수 없습니다.", HttpStatus.BAD_REQUEST)
        meetingNoteContentSupport.requireJsonObject(request.content)
        val template = templateRepository.findById(request.templateId).orElseThrow { templateNotFoundException() }
        if (template.channelId != channelId) {
            throw templateNotFoundException()
        }
        // 조회 직후 다른 요청이 템플릿을 삭제하면 FK가 저장을 막으므로 여기서 flush해 404로 응답한다.
        val note = try {
            meetingNoteRepository.saveAndFlush(
                MeetingNote(
                    channelId = channelId,
                    templateId = request.templateId,
                    title = request.title,
                    content = request.content,
                    createdBy = userId,
                ),
            )
        } catch (e: DataIntegrityViolationException) {
            throw templateNotFoundException()
        }
        return MeetingNoteResponse.of(note)
    }

    private fun templateNotFoundException() = ExpectedException("템플릿을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
}
