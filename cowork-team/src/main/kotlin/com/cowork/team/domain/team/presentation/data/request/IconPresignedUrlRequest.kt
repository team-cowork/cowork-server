package com.cowork.team.domain.team.presentation.data.request

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "팀 아이콘 Presigned URL 발급 요청")
data class IconPresignedUrlRequest(
    @param:Schema(description = "업로드할 파일의 Content-Type (image/jpeg, image/png, image/webp)") val contentType: String,
)
