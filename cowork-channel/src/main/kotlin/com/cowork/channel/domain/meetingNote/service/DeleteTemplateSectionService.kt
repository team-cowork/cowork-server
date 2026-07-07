package com.cowork.channel.domain.meetingNote.service

interface DeleteTemplateSectionService {
    fun deleteSection(userId: Long, channelId: Long, templateId: Long, sectionId: Long)
}
