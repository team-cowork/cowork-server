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
    private val sectionRepository: TemplateSectionRepository,
    private val meetingNoteAccessGuard: MeetingNoteAccessGuard,
    private val lookupSupport: MeetingNoteTemplateLookupSupport,
) : DeleteTemplateSectionService {

    override fun deleteSection(userId: Long, channelId: Long, templateId: Long, sectionId: Long) {
        meetingNoteAccessGuard.requireChannelMember(channelId, userId)
        val template = lookupSupport.findTemplateOrThrow(templateId)
        lookupSupport.requireTemplateOwnership(template, channelId)
        val section = lookupSupport.findSectionOrThrow(sectionId)
        lookupSupport.requireSectionOwnership(section, templateId)
        sectionRepository.delete(section)
    }
}
