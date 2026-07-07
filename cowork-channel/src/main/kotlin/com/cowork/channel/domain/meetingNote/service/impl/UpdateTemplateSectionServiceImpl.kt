package com.cowork.channel.domain.meetingNote.service.impl

import com.cowork.channel.domain.meetingNote.presentation.data.request.UpdateTemplateSectionRequest
import com.cowork.channel.domain.meetingNote.presentation.data.response.TemplateSectionResponse
import com.cowork.channel.domain.meetingNote.service.MeetingNoteAccessGuard
import com.cowork.channel.domain.meetingNote.service.UpdateTemplateSectionService
import com.cowork.channel.domain.meetingNote.service.support.MeetingNoteTemplateLookupSupport
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class UpdateTemplateSectionServiceImpl(
    private val meetingNoteAccessGuard: MeetingNoteAccessGuard,
    private val lookupSupport: MeetingNoteTemplateLookupSupport,
) : UpdateTemplateSectionService {

    override fun updateSection(
        userId: Long,
        channelId: Long,
        templateId: Long,
        sectionId: Long,
        request: UpdateTemplateSectionRequest,
    ): TemplateSectionResponse {
        meetingNoteAccessGuard.requireChannelMember(channelId, userId)
        val template = lookupSupport.findTemplateOrThrow(templateId)
        lookupSupport.requireTemplateOwnership(template, channelId)
        val section = lookupSupport.findSectionOrThrow(sectionId)
        lookupSupport.requireSectionOwnership(section, templateId)
        section.update(
            title = request.title,
            type = request.type?.let { lookupSupport.parseSectionType(it) },
            placeholder = request.placeholder,
            isRequired = request.isRequired,
        )
        return TemplateSectionResponse.of(section)
    }
}
