package com.cowork.channel.domain.meetingNote.presentation.data.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class SummarizeMeetingNoteRequest(
    @param:Schema(description = "회의록 제목")
    @field:Size(max = 200, message = "회의록 제목은 200자 이하여야 합니다.")
    val title: String = "",
    @param:Schema(description = "섹션 제목과 내용을 포함한 회의록 텍스트. 앞 12,000자까지 요약합니다.")
    @field:NotBlank(message = "요약할 회의록 내용을 입력해 주십시오.")
    val content: String,
)
