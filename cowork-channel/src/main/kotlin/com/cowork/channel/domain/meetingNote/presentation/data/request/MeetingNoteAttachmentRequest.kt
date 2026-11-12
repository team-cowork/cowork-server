package com.cowork.channel.domain.meetingNote.presentation.data.request

import io.swagger.v3.oas.annotations.media.Schema

data class MeetingNoteAttachmentRequest(
    @param:Schema(description = "채팅 업로드 완료 API가 반환한 확정 objectKey")
    val objectKey: String,
    @param:Schema(description = "다운로드 파일명", example = "회의자료.pdf")
    val name: String,
)
