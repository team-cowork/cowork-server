package com.cowork.team.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "팀 아이콘 교체 요청")
data class UpdateIconRequest(
    @Schema(description = "confirm 응답에서 받은 iconUrl") val iconUrl: String,
)
