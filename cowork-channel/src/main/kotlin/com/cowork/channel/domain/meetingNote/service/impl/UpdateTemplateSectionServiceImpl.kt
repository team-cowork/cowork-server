package com.cowork.channel.domain.meetingNote.service.impl

import com.cowork.channel.domain.meetingNote.presentation.data.request.UpdateTemplateSectionRequest
import com.cowork.channel.domain.meetingNote.presentation.data.response.TemplateSectionResponse
import com.cowork.channel.domain.meetingNote.repository.MeetingNoteRepository
import com.cowork.channel.domain.meetingNote.service.MeetingNoteAccessGuard
import com.cowork.channel.domain.meetingNote.service.UpdateTemplateSectionService
import com.cowork.channel.domain.meetingNote.service.support.MeetingNoteContentSupport
import com.cowork.channel.domain.meetingNote.service.support.MeetingNoteTemplateLookupSupport
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class UpdateTemplateSectionServiceImpl(
    private val meetingNoteRepository: MeetingNoteRepository,
    private val meetingNoteAccessGuard: MeetingNoteAccessGuard,
    private val lookupSupport: MeetingNoteTemplateLookupSupport,
    private val meetingNoteContentSupport: MeetingNoteContentSupport,
) : UpdateTemplateSectionService {

    override fun updateSection(
        userId: Long,
        channelId: Long,
        templateId: Long,
        sectionId: Long,
        request: UpdateTemplateSectionRequest,
    ): TemplateSectionResponse {
        meetingNoteAccessGuard.requireChannelAccessForUpdate(channelId, userId)
        val template = lookupSupport.findTemplateOrThrow(templateId)
        lookupSupport.requireTemplateOwnership(template, channelId)
        val section = lookupSupport.findSectionOrThrow(sectionId)
        lookupSupport.requireSectionOwnership(section, templateId)
        val previousTitle = section.title
        request.title?.takeIf { it != previousTitle }?.let { lookupSupport.requireUniqueSectionTitle(templateId, it) }
        section.update(
            title = request.title,
            type = request.type?.let { lookupSupport.parseSectionType(it) },
            placeholder = request.placeholder,
            isRequired = request.isRequired,
        )
        if (section.title != previousTitle) {
            renameNoteContentKeys(templateId, previousTitle, section.title)
        }
        return TemplateSectionResponse.of(section)
    }

    // 회의록 content는 섹션 제목을 키로 쓰므로, 이 템플릿으로 쓴 회의록의 키도 함께 옮겨야 내용이 섹션에 남는다.
    private fun renameNoteContentKeys(templateId: Long, from: String, to: String) {
        meetingNoteRepository.findAllByTemplateId(templateId).forEach { note ->
            meetingNoteContentSupport.renameKey(note.content, from, to)?.let { note.update(title = null, content = it) }
        }
    }
}
