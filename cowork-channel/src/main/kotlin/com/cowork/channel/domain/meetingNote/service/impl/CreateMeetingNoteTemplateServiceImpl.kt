package com.cowork.channel.domain.meetingNote.service.impl

import com.cowork.channel.domain.meetingNote.entity.MeetingNoteTemplate
import com.cowork.channel.domain.meetingNote.presentation.data.request.CreateMeetingNoteTemplateRequest
import com.cowork.channel.domain.meetingNote.presentation.data.response.MeetingNoteTemplateResponse
import com.cowork.channel.domain.meetingNote.repository.MeetingNoteTemplateRepository
import com.cowork.channel.domain.meetingNote.service.CreateMeetingNoteTemplateService
import com.cowork.channel.domain.meetingNote.service.MeetingNoteAccessGuard
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class CreateMeetingNoteTemplateServiceImpl(
    private val templateRepository: MeetingNoteTemplateRepository,
    private val meetingNoteAccessGuard: MeetingNoteAccessGuard,
) : CreateMeetingNoteTemplateService {

    override fun createTemplate(
        userId: Long,
        channelId: Long,
        request: CreateMeetingNoteTemplateRequest,
    ): MeetingNoteTemplateResponse {
        meetingNoteAccessGuard.requireChannelMember(channelId, userId)
        val template = templateRepository.save(
            MeetingNoteTemplate(
                channelId = channelId,
                name = request.name,
                isActive = false,
                createdBy = userId,
            ),
        )
        return MeetingNoteTemplateResponse.of(template, emptyList())
    }
}
