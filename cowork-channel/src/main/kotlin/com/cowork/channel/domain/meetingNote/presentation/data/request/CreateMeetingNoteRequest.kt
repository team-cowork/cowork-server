package com.cowork.channel.domain.meetingNote.presentation.data.request

import com.cowork.channel.domain.meetingNote.entity.MeetingNote
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

@Schema(requiredProperties = ["title", "content"])
data class CreateMeetingNoteRequest(
    @get:Schema(description = "템플릿 ID", example = "1", nullable = true)
    val templateId: Long?,
    @get:Schema(description = "회의록 제목", example = "2024-01-01 팀 주간 회의")
    val title: String,
    @get:Schema(description = "섹션 제목별 본문", contentMediaType = "application/json")
    @field:Size(max = MeetingNote.MAX_CONTENT_LENGTH, message = "회의록 본문은 {max}자 이하여야 합니다.")
    val content: String,
    @get:Schema(description = "회의록 첨부파일")
    val attachments: List<MeetingNoteAttachmentRequest> = emptyList(),
)
