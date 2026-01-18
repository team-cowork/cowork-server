package com.cowork.channel.meetingnote.dto

import com.cowork.channel.meetingnote.domain.MeetingNoteTemplate
import com.cowork.channel.meetingnote.domain.SectionType
import com.cowork.channel.meetingnote.domain.TemplateSection
import java.time.LocalDateTime

data class CreateTemplateRequest(
    val name: String,
    val sections: List<CreateSectionRequest> = emptyList(),
)

data class UpdateTemplateRequest(
    val name: String,
)

data class CreateSectionRequest(
    val title: String,
    val type: SectionType,
    val placeholder: String? = null,
    val required: Boolean = false,
)

data class UpdateSectionRequest(
    val title: String? = null,
    val type: SectionType? = null,
    val placeholder: String? = null,
    val required: Boolean? = null,
    val position: Int? = null,
)

data class SectionResponse(
    val id: Long,
    val title: String,
    val type: SectionType,
    val placeholder: String?,
    val required: Boolean,
    val position: Int,
) {
    companion object {
        fun from(section: TemplateSection) = SectionResponse(
            id = section.id,
            title = section.title,
            type = section.type,
            placeholder = section.placeholder,
            required = section.required,
            position = section.position,
        )
    }
}

data class TemplateResponse(
    val id: Long,
    val channelId: Long,
    val name: String,
    val isActive: Boolean,
    val createdBy: Long,
    val sections: List<SectionResponse>,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun of(template: MeetingNoteTemplate, sections: List<TemplateSection>) = TemplateResponse(
            id = template.id,
            channelId = template.channelId,
            name = template.name,
            isActive = template.isActive,
            createdBy = template.createdBy,
            sections = sections.map(SectionResponse::from),
            createdAt = template.createdAt,
        )
    }
}
