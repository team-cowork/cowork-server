package com.cowork.channel.domain.meetingNote.presentation.data.response

import com.cowork.channel.domain.meetingNote.entity.MeetingNoteTemplate
import java.time.LocalDateTime

data class MeetingNoteTemplateResponse(
    val id: Long,
    val channelId: Long,
    val name: String,
    val isActive: Boolean,
    val createdBy: Long,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val sections: List<TemplateSectionResponse>,
) {
    companion object {
        fun of(template: MeetingNoteTemplate, sections: List<TemplateSectionResponse>) = MeetingNoteTemplateResponse(
            id = template.id,
            channelId = template.channelId,
            name = template.name,
            isActive = template.isActive,
            createdBy = template.createdBy,
            createdAt = template.createdAt,
            updatedAt = template.updatedAt,
            sections = sections,
        )
    }
}
