package com.cowork.channel.domain.meetingNote.service

import com.cowork.channel.domain.meetingNote.presentation.data.request.CreateMeetingNoteTemplateRequest
import com.cowork.channel.domain.meetingNote.presentation.data.response.MeetingNoteTemplateResponse

interface CreateMeetingNoteTemplateService {
    fun createTemplate(userId: Long, channelId: Long, request: CreateMeetingNoteTemplateRequest): MeetingNoteTemplateResponse
}
