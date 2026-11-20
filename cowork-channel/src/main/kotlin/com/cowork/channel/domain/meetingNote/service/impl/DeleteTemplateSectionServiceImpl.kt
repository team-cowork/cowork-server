package com.cowork.channel.domain.meetingNote.service.impl

import com.cowork.channel.domain.meetingNote.repository.TemplateSectionRepository
import com.cowork.channel.domain.meetingNote.service.DeleteTemplateSectionService
import com.cowork.channel.domain.meetingNote.service.MeetingNoteAccessGuard
import com.cowork.channel.domain.meetingNote.service.support.MeetingNoteTemplateLookupSupport
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class DeleteTemplateSectionServiceImpl(
    private val templateSectionRepository: TemplateSectionRepository,
    private val meetingNoteAccessGuard: MeetingNoteAccessGuard,
    private val meetingNoteTemplateLookupSupport: MeetingNoteTemplateLookupSupport,
) : DeleteTemplateSectionService {

    override fun deleteSection(userId: Long, channelId: Long, templateId: Long, sectionId: Long) {
        meetingNoteAccessGuard.requireWritableChannelAccess(channelId, userId)
        val template = meetingNoteTemplateLookupSupport.findTemplateOrThrow(templateId)
        meetingNoteTemplateLookupSupport.requireTemplateOwnership(template, channelId)
        val section = meetingNoteTemplateLookupSupport.findSectionOrThrow(sectionId)
        meetingNoteTemplateLookupSupport.requireSectionOwnership(section, templateId)
        templateSectionRepository.delete(section)
    }
}
