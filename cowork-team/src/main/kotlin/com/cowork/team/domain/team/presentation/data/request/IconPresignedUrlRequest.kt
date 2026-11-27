package com.cowork.team.domain.team.presentation.data.request

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "팀 아이콘 Presigned URL 발급 요청")
data class IconPresignedUrlRequest(
    @get:Schema(description = "아이콘을 변경할 팀 ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    val teamId: Long,
    @get:Schema(
        description = "업로드할 파일의 Content-Type",
        allowableValues = ["image/jpeg", "image/png", "image/webp"],
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val contentType: String,
)
