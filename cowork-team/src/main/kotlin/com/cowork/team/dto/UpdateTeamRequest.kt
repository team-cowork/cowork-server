package com.cowork.team.dto

import io.swagger.v3.oas.annotations.media.Schema

data class UpdateTeamRequest(
    @Schema(description = "팀 이름", example = "코워크팀")
    val name: String?,
    @Schema(description = "팀 설명", example = "협업 서비스 개발팀")
    val description: String?,
    @Schema(description = "팀 아이콘 URL", example = "https://example.com/icon.png")
    val iconUrl: String?,
)