package com.cowork.channel.domain.meetingNote.service.impl

import com.cowork.channel.domain.meetingNote.repository.MeetingNoteRepository
import com.cowork.channel.domain.meetingNote.repository.MeetingNoteTemplateRepository
import com.cowork.channel.domain.meetingNote.service.DeleteMeetingNoteTemplateService
import com.cowork.channel.domain.meetingNote.service.MeetingNoteAccessGuard
import com.cowork.channel.domain.meetingNote.service.support.MeetingNoteTemplateLookupSupport
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
@Transactional
class DeleteMeetingNoteTemplateServiceImpl(
    private val templateRepository: MeetingNoteTemplateRepository,
    private val meetingNoteRepository: MeetingNoteRepository,
    private val meetingNoteAccessGuard: MeetingNoteAccessGuard,
    private val lookupSupport: MeetingNoteTemplateLookupSupport,
) : DeleteMeetingNoteTemplateService {

    override fun deleteTemplate(userId: Long, channelId: Long, templateId: Long) {
        meetingNoteAccessGuard.requireChannelAccess(channelId, userId)
        val template = lookupSupport.findTemplateOrThrow(templateId)
        lookupSupport.requireTemplateOwnership(template, channelId)
        if (template.isActive) {
            throw ExpectedException("활성 템플릿은 삭제할 수 없습니다. 다른 템플릿을 활성화한 후 삭제해 주세요.", HttpStatus.BAD_REQUEST)
        }
        if (meetingNoteRepository.existsByTemplateId(template.id)) {
            throw templateInUseException()
        }
        // 확인 직후 다른 요청이 같은 템플릿으로 회의록을 만들면 FK가 삭제를 막으므로 여기서 flush해 같은 400으로 응답한다.
        try {
            templateRepository.delete(template)
            templateRepository.flush()
        } catch (e: DataIntegrityViolationException) {
            throw templateInUseException()
        }
    }

    private fun templateInUseException() =
        ExpectedException("회의록이 작성된 템플릿은 삭제할 수 없습니다.", HttpStatus.BAD_REQUEST)
}
