package com.cowork.channel.domain.meetingNote.presentation.data.request

import com.cowork.channel.domain.meetingNote.entity.MeetingNote
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

data class CreateMeetingNoteRequest(
    @param:Schema(description = "사용할 템플릿 ID. null이면 템플릿 없이 작성합니다.", example = "1", nullable = true)
    val templateId: Long?,
    @param:Schema(description = "회의록 제목", example = "2024-01-01 팀 주간 회의")
    val title: String,
    @param:Schema(description = "섹션별 작성 내용 JSON 블록", example = "{\"회의 제목\": \"API 설계 논의\", \"참석자\": \"홍길동, 김철수\"}")
    @field:Size(max = MeetingNote.MAX_CONTENT_LENGTH, message = "회의록 본문은 {max}자 이하여야 합니다.")
    val content: String,
    @param:Schema(description = "회의록 첨부파일. 업로드 완료 후 반환된 objectKey를 사용합니다.")
    val attachments: List<MeetingNoteAttachmentRequest> = emptyList(),
)
