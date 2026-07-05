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
    private val templateRepository: MeetingNoteTemplateRepository,
    private val sectionRepository: TemplateSectionRepository,
    private val meetingNoteAccessGuard: MeetingNoteAccessGuard,
) : ListMeetingNoteTemplatesService {

    override fun listTemplates(userId: Long, channelId: Long): List<MeetingNoteTemplateResponse> {
        meetingNoteAccessGuard.requireChannelMember(channelId, userId)
        val templates = templateRepository.findAllByChannelIdOrderByIdAsc(channelId)
        if (templates.isEmpty()) return emptyList()
        val sectionsByTemplateId = sectionRepository
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
