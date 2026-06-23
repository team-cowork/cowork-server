package com.cowork.channel.dto

import io.swagger.v3.oas.annotations.media.Schema

data class UpdateMeetingNoteRequest(
    @param:Schema(description = "회의록 제목", example = "2024-01-01 팀 주간 회의")
    val title: String?,
    @param:Schema(description = "섹션별 작성 내용 JSON 블록", example = "{\"회의 제목\": \"API 설계 논의\", \"참석자\": \"홍길동, 김철수\"}")
    val content: String?,
)
