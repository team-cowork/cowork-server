package com.cowork.channel.domain.meetingNote.service.impl

import com.cowork.channel.domain.meetingNote.presentation.data.response.MeetingNoteTemplateResponse
import com.cowork.channel.domain.meetingNote.presentation.data.response.TemplateSectionResponse
import com.cowork.channel.domain.meetingNote.repository.MeetingNoteTemplateRepository
import com.cowork.channel.domain.meetingNote.repository.TemplateSectionRepository
import com.cowork.channel.domain.meetingNote.service.ListMeetingNoteTemplatesService
import com.cowork.channel.domain.meetingNote.service.MeetingNoteAccessGuard
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class ListMeetingNoteTemplatesServiceImpl(
    private val meetingNoteTemplateRepository: MeetingNoteTemplateRepository,
    private val templateSectionRepository: TemplateSectionRepository,
    private val meetingNoteAccessGuard: MeetingNoteAccessGuard,
) : ListMeetingNoteTemplatesService {

    override fun listTemplates(userId: Long, channelId: Long): List<MeetingNoteTemplateResponse> {
        meetingNoteAccessGuard.requireChannelAccess(channelId, userId)
        val templates = meetingNoteTemplateRepository.findAllByChannelIdOrderByIdAsc(channelId)
        if (templates.isEmpty()) return emptyList()
        val sectionsByTemplateId = templateSectionRepository
            .findAllByTemplateIdInOrderByIdAsc(templates.map { it.id })
            .groupBy { it.templateId }
        return templates.map { template ->
            MeetingNoteTemplateResponse.of(
                template,
                (sectionsByTemplateId[template.id] ?: emptyList()).map { TemplateSectionResponse.of(it) },
            )
        }
    }
}
