package com.cowork.channel.domain.meetingNote.presentation.data.response

import com.cowork.channel.domain.meetingNote.entity.MeetingNote
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

data class MeetingNoteResponse(
    @field:Schema(description = "회의록 ID", example = "1")
    val id: Long,
    @field:Schema(description = "채널 ID", example = "1")
    val channelId: Long,
    @field:Schema(description = "사용된 템플릿 ID", example = "1")
    val templateId: Long,
    @field:Schema(description = "회의록 제목", example = "2024-01-01 팀 주간 회의")
    val title: String,
    @field:Schema(description = "섹션별 작성 내용 JSON 블록", example = "{\"회의 제목\": \"API 설계 논의\", \"참석자\": \"홍길동, 김철수\"}")
    val content: String,
    @field:Schema(description = "작성자 ID", example = "1")
    val createdBy: Long,
    @field:Schema(description = "작성 일시")
    val createdAt: LocalDateTime,
    @field:Schema(description = "수정 일시")
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun of(note: MeetingNote) = MeetingNoteResponse(
            id = note.id,
            channelId = note.channelId,
            templateId = note.templateId,
            title = note.title,
            content = note.content,
            createdBy = note.createdBy,
            createdAt = note.createdAt,
            updatedAt = note.updatedAt,
        )
    }
}
