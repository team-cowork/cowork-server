package com.cowork.channel.domain.meetingNote.service.impl

import com.cowork.channel.domain.meetingNote.presentation.data.response.MeetingNoteTemplateResponse
import com.cowork.channel.domain.meetingNote.presentation.data.response.TemplateSectionResponse
import com.cowork.channel.domain.meetingNote.repository.TemplateSectionRepository
import com.cowork.channel.domain.meetingNote.service.GetMeetingNoteTemplateService
import com.cowork.channel.domain.meetingNote.service.MeetingNoteAccessGuard
import com.cowork.channel.domain.meetingNote.service.support.MeetingNoteTemplateLookupSupport
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class GetMeetingNoteTemplateServiceImpl(
    private val sectionRepository: TemplateSectionRepository,
    private val meetingNoteAccessGuard: MeetingNoteAccessGuard,
    private val lookupSupport: MeetingNoteTemplateLookupSupport,
) : GetMeetingNoteTemplateService {

    override fun getTemplate(userId: Long, channelId: Long, templateId: Long): MeetingNoteTemplateResponse {
        meetingNoteAccessGuard.requireChannelMember(channelId, userId)
        val template = lookupSupport.findTemplateOrThrow(templateId)
        lookupSupport.requireTemplateOwnership(template, channelId)
        val sections = sectionRepository.findAllByTemplateIdOrderByIdAsc(templateId)
            .map { TemplateSectionResponse.of(it) }
        return MeetingNoteTemplateResponse.of(template, sections)
    }
}
