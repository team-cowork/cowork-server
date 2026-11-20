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
class ActivateMeetingNoteTemplateServiceImpl(
    private val meetingNoteTemplateRepository: MeetingNoteTemplateRepository,
    private val templateSectionRepository: TemplateSectionRepository,
    private val meetingNoteAccessGuard: MeetingNoteAccessGuard,
    private val meetingNoteTemplateLookupSupport: MeetingNoteTemplateLookupSupport,
) : ActivateMeetingNoteTemplateService {

    @Transactional
    override fun activateTemplate(userId: Long, channelId: Long, templateId: Long): MeetingNoteTemplateResponse {
        meetingNoteAccessGuard.requireChannelAccessForUpdate(channelId, userId)
        val template = meetingNoteTemplateLookupSupport.findTemplateOrThrow(templateId)
        meetingNoteTemplateLookupSupport.requireTemplateOwnership(template, channelId)
        meetingNoteTemplateRepository.findAllByChannelIdAndIsActiveTrue(channelId)
            .filter { it.id != templateId }
            .forEach { it.deactivate() }
        template.activate()
        val sections = templateSectionRepository.findAllByTemplateIdOrderByIdAsc(templateId)
            .map { TemplateSectionResponse.of(it) }
        return MeetingNoteTemplateResponse.of(template, sections)
    }
}
