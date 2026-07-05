package com.cowork.channel.domain.meetingNote.service

import com.cowork.channel.domain.meetingNote.presentation.data.request.CreateTemplateSectionRequest
import com.cowork.channel.domain.meetingNote.presentation.data.response.TemplateSectionResponse

interface AddTemplateSectionService {
    fun addSection(userId: Long, channelId: Long, templateId: Long, request: CreateTemplateSectionRequest): TemplateSectionResponse
}
