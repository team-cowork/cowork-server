package com.cowork.channel.domain.meetingNote.service

interface DeleteMeetingNoteTemplateService {
    fun deleteTemplate(userId: Long, channelId: Long, templateId: Long)
}
