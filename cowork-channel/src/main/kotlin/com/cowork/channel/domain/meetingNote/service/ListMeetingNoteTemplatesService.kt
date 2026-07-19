package com.cowork.channel.domain.meetingNote.service

import com.cowork.channel.domain.meetingNote.presentation.data.response.MeetingNoteTemplateResponse

interface ListMeetingNoteTemplatesService {
    fun listTemplates(userId: Long, channelId: Long): List<MeetingNoteTemplateResponse>
}
