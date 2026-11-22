package com.cowork.channel.domain.meetingNote.service.impl

import com.cowork.channel.domain.meetingNote.entity.TemplateSection
import com.cowork.channel.domain.meetingNote.presentation.data.request.CreateTemplateSectionRequest
import com.cowork.channel.domain.meetingNote.presentation.data.response.TemplateSectionResponse
import com.cowork.channel.domain.meetingNote.repository.TemplateSectionRepository
import com.cowork.channel.domain.meetingNote.service.AddTemplateSectionService
import com.cowork.channel.domain.meetingNote.service.MeetingNoteAccessGuard
import com.cowork.channel.domain.meetingNote.service.support.MeetingNoteTemplateLookupSupport
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class AddTemplateSectionServiceImpl(
    private val templateSectionRepository: TemplateSectionRepository,
    private val meetingNoteAccessGuard: MeetingNoteAccessGuard,
    private val meetingNoteTemplateLookupSupport: MeetingNoteTemplateLookupSupport,
) : AddTemplateSectionService {

    override fun addSection(
        userId: Long,
        channelId: Long,
        templateId: Long,
        request: CreateTemplateSectionRequest,
    ): TemplateSectionResponse {
        meetingNoteAccessGuard.requireChannelAccessForUpdate(channelId, userId)
        val template = meetingNoteTemplateLookupSupport.findTemplateOrThrow(templateId)
        meetingNoteTemplateLookupSupport.requireTemplateOwnership(template, channelId)
        meetingNoteTemplateLookupSupport.requireUniqueSectionTitle(templateId, request.title)
        val section = templateSectionRepository.save(
            TemplateSection(
                templateId = templateId,
                title = request.title,
                type = meetingNoteTemplateLookupSupport.parseSectionType(request.type),
                placeholder = request.placeholder,
                isRequired = request.isRequired,
            ),
        )
        return TemplateSectionResponse.of(section)
    }
}
