package com.cowork.channel.domain.meetingNote.presentation.data.request

import com.cowork.channel.domain.meetingNote.entity.MeetingNote
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

data class UpdateMeetingNoteRequest(
    @param:Schema(description = "회의록 제목", example = "2024-01-01 팀 주간 회의")
    val title: String?,
    @param:Schema(description = "섹션별 작성 내용 JSON 블록. 최대 100,000자이며 길이는 UTF-16 코드 단위로 세므로 이모지 등 일부 문자는 2자로 센다", example = "{\"회의 제목\": \"API 설계 논의\", \"참석자\": \"홍길동, 김철수\"}")
    @field:Size(max = MeetingNote.MAX_CONTENT_LENGTH, message = "회의록 본문은 {max}자 이하여야 합니다.")
    val content: String?,
    @param:Schema(description = "첨부파일 전체 목록. 생략하면 유지하고 빈 배열이면 모두 해제합니다.")
    val attachments: List<MeetingNoteAttachmentRequest>? = null,
)
