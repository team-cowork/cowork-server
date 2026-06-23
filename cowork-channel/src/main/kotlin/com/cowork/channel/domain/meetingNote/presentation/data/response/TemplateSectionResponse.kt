package com.cowork.channel.domain.meetingNote.presentation.data.response

import com.cowork.channel.domain.meetingNote.entity.TemplateSection
import java.time.LocalDateTime

data class TemplateSectionResponse(
    val id: Long,
    val templateId: Long,
    val title: String,
    val type: String,
    val placeholder: String?,
    val isRequired: Boolean,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun of(section: TemplateSection) = TemplateSectionResponse(
            id = section.id,
            templateId = section.templateId,
            title = section.title,
            type = section.type.name,
            placeholder = section.placeholder,
            isRequired = section.isRequired,
            createdAt = section.createdAt,
            updatedAt = section.updatedAt,
        )
    }
}
