package com.cowork.channel.meetingnote.dto

import com.cowork.channel.meetingnote.domain.MeetingNote
import com.cowork.channel.meetingnote.domain.MeetingNoteTemplate
import com.cowork.channel.meetingnote.domain.NoteSection
import com.cowork.channel.meetingnote.domain.SectionType
import com.cowork.channel.meetingnote.domain.TemplateSection
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

data class CreateTemplateRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val name: String,
    @field:Valid
    @field:Size(max = 30)
    val sections: List<CreateSectionRequest> = emptyList(),
)

data class UpdateTemplateRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val name: String,
)

data class CreateSectionRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val title: String,
    @field:NotNull
    val type: SectionType,
    @field:Size(max = 255)
    val placeholder: String? = null,
    val required: Boolean = false,
)

data class UpdateSectionRequest(
    @field:Size(min = 1, max = 100)
    val title: String? = null,
    val type: SectionType? = null,
    @field:Size(max = 255)
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

data class CreateMeetingNoteRequest(
    @field:NotBlank
    @field:Size(max = 200)
    val title: String,
    val values: Map<Long, Any?> = emptyMap(),
)

data class MeetingNoteSummaryResponse(
    val id: Long,
    val title: String,
    val createdBy: Long,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(note: MeetingNote) = MeetingNoteSummaryResponse(note.id, note.title, note.createdBy, note.createdAt)
    }
}

data class MeetingNoteResponse(
    val id: Long,
    val channelId: Long,
    val templateId: Long,
    val title: String,
    val content: List<NoteSection>,
    val createdBy: Long,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(note: MeetingNote) = MeetingNoteResponse(
            id = note.id,
            channelId = note.channelId,
            templateId = note.templateId,
            title = note.title,
            content = note.content,
            createdBy = note.createdBy,
            createdAt = note.createdAt,
        )
    }
}

data class MeetingNotePageResponse(
    val notes: List<MeetingNoteSummaryResponse>,
    val nextCursor: Long?,
)
