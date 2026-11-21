package com.cowork.channel.domain.meetingNote.presentation.data.response

import io.swagger.v3.oas.annotations.media.Schema

data class MeetingNoteSummaryResponse(
    @field:Schema(description = "회의 핵심 요약")
    val summary: String,
    @field:Schema(description = "회의에서 결정한 사항")
    val decisions: List<String>,
    @field:Schema(description = "GitHub 이슈로 만들 수 있는 할 일")
    val actionItems: List<ActionItem>,
    @field:Schema(description = "입력이 길어 본문의 앞부분만 요약했는지 여부")
    val truncated: Boolean,
) {
    data class ActionItem(
        @field:Schema(description = "할 일 제목")
        val title: String,
        @field:Schema(description = "회의에서 언급한 배경, 담당 또는 기한")
        val body: String,
    )
}
