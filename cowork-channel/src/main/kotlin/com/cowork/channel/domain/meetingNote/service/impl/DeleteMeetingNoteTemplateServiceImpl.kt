package com.cowork.channel.domain.meetingNote.service.impl

import com.cowork.channel.domain.meetingNote.repository.MeetingNoteTemplateRepository
import com.cowork.channel.domain.meetingNote.service.DeleteMeetingNoteTemplateService
import com.cowork.channel.domain.meetingNote.service.MeetingNoteAccessGuard
import com.cowork.channel.domain.meetingNote.service.support.MeetingNoteTemplateLookupSupport
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
@Transactional
class DeleteMeetingNoteTemplateServiceImpl(
    private val templateRepository: MeetingNoteTemplateRepository,
    private val meetingNoteAccessGuard: MeetingNoteAccessGuard,
    private val lookupSupport: MeetingNoteTemplateLookupSupport,
) : DeleteMeetingNoteTemplateService {

    override fun deleteTemplate(userId: Long, channelId: Long, templateId: Long) {
        meetingNoteAccessGuard.requireChannelMember(channelId, userId)
        val template = lookupSupport.findTemplateOrThrow(templateId)
        lookupSupport.requireTemplateOwnership(template, channelId)
        if (template.isActive) {
            throw ExpectedException("활성 템플릿은 삭제할 수 없습니다. 다른 템플릿을 활성화한 후 삭제해 주세요.", HttpStatus.BAD_REQUEST)
        }
        templateRepository.delete(template)
    }
}
