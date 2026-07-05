package com.cowork.channel.domain.meetingNote.service

import com.cowork.channel.domain.meetingNote.presentation.data.response.MeetingNoteTemplateResponse

interface GetMeetingNoteTemplateService {
    fun getTemplate(userId: Long, channelId: Long, templateId: Long): MeetingNoteTemplateResponse
}
