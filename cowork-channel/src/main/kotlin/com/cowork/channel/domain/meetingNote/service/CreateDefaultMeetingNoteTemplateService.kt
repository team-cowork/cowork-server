package com.cowork.channel.domain.meetingNote.service

import com.cowork.channel.domain.channel.entity.Channel

interface CreateDefaultMeetingNoteTemplateService {
    fun createDefaultTemplate(channel: Channel)
}
