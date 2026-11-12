package com.cowork.channel.domain.meetingNote.presentation.data.response

import io.swagger.v3.oas.annotations.media.Schema

data class MeetingNoteAttachmentResponse(
    @field:Schema(description = "첨부파일 다운로드 URL 발급에 사용할 objectKey")
    val objectKey: String,
    @field:Schema(description = "다운로드 파일명")
    val name: String,
)
