package com.cowork.channel.domain.meetingNote.service

import com.cowork.channel.domain.meetingNote.presentation.data.request.UpdateTemplateSectionRequest
import com.cowork.channel.domain.meetingNote.presentation.data.response.TemplateSectionResponse

interface UpdateTemplateSectionService {
    fun updateSection(userId: Long, channelId: Long, templateId: Long, sectionId: Long, request: UpdateTemplateSectionRequest): TemplateSectionResponse
}
