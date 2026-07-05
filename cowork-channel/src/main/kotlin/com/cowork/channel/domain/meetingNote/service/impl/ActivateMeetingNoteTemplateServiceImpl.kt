package com.cowork.channel.domain.meetingNote.service.impl

import com.cowork.channel.domain.meetingNote.presentation.data.response.MeetingNoteTemplateResponse
import com.cowork.channel.domain.meetingNote.presentation.data.response.TemplateSectionResponse
import com.cowork.channel.domain.meetingNote.repository.MeetingNoteTemplateRepository
import com.cowork.channel.domain.meetingNote.repository.TemplateSectionRepository
import com.cowork.channel.domain.meetingNote.service.ActivateMeetingNoteTemplateService
import com.cowork.channel.domain.meetingNote.service.MeetingNoteAccessGuard
import com.cowork.channel.domain.meetingNote.service.support.MeetingNoteTemplateLookupSupport
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class ActivateMeetingNoteTemplateServiceImpl(
    private val templateRepository: MeetingNoteTemplateRepository,
    private val sectionRepository: TemplateSectionRepository,
    private val meetingNoteAccessGuard: MeetingNoteAccessGuard,
    private val lookupSupport: MeetingNoteTemplateLookupSupport,
) : ActivateMeetingNoteTemplateService {

    override fun activateTemplate(userId: Long, channelId: Long, templateId: Long): MeetingNoteTemplateResponse {
        meetingNoteAccessGuard.requireChannelMember(channelId, userId)
        val template = lookupSupport.findTemplateOrThrow(templateId)
        lookupSupport.requireTemplateOwnership(template, channelId)
        templateRepository.findByChannelIdAndIsActiveTrue(channelId)
            ?.takeIf { it.id != templateId }
            ?.deactivate()
        template.activate()
        val sections = sectionRepository.findAllByTemplateIdOrderByIdAsc(templateId)
            .map { TemplateSectionResponse.of(it) }
        return MeetingNoteTemplateResponse.of(template, sections)
    }
}
