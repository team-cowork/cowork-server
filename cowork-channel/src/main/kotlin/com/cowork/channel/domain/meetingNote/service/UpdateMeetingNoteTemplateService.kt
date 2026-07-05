package com.cowork.channel.domain.meetingNote.service

import com.cowork.channel.domain.meetingNote.presentation.data.request.UpdateMeetingNoteTemplateRequest
import com.cowork.channel.domain.meetingNote.presentation.data.response.MeetingNoteTemplateResponse

interface UpdateMeetingNoteTemplateService {
    fun updateTemplate(
        userId: Long,
        channelId: Long,
        templateId: Long,
        request: UpdateMeetingNoteTemplateRequest,
    ): MeetingNoteTemplateResponse
}
