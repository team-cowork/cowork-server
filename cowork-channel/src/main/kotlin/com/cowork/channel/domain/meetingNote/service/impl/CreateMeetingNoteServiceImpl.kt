package com.cowork.channel.domain.meetingNote.service.impl

import com.cowork.channel.domain.meetingNote.entity.MeetingNote
import com.cowork.channel.domain.meetingNote.presentation.data.request.CreateMeetingNoteRequest
import com.cowork.channel.domain.meetingNote.presentation.data.response.MeetingNoteResponse
import com.cowork.channel.domain.meetingNote.repository.MeetingNoteRepository
import com.cowork.channel.domain.meetingNote.repository.MeetingNoteTemplateRepository
import com.cowork.channel.domain.meetingNote.service.CreateMeetingNoteService
import com.cowork.channel.domain.meetingNote.service.MeetingNoteAccessGuard
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
) : CreateMeetingNoteService {

    override fun createNote(userId: Long, channelId: Long, request: CreateMeetingNoteRequest): MeetingNoteResponse {
        meetingNoteAccessGuard.requireChannelMember(channelId, userId)
        val template = templateRepository.findById(request.templateId).orElseThrow {
            ExpectedException("템플릿을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }
        if (template.channelId != channelId) {
            throw ExpectedException("템플릿을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }
        val note = meetingNoteRepository.save(
            MeetingNote(
                channelId = channelId,
                templateId = request.templateId,
                title = request.title,
                content = request.content,
                createdBy = userId,
            ),
        )
        return MeetingNoteResponse.of(note)
    }
}
