package com.cowork.user.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Presigned URL 발급 요청")
data class PresignedUrlRequest(
    @Schema(description = "업로드할 파일의 Content-Type (예: image/jpeg, image/png, image/webp)") val contentType: String,
)
