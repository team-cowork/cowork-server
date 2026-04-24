package com.cowork.team.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "팀 아이콘 업로드 확인 응답")
data class IconConfirmResponse(
    @Schema(description = "업로드 완료 후 사용할 CDN URL") val iconUrl: String,
)
