package com.cowork.user.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Presigned URL 발급 응답")
data class PresignedUrlResponse(
    @Schema(description = "S3 Presigned PUT URL — 이 URL로 직접 파일을 PUT 요청") val uploadUrl: String,
    @Schema(description = "업로드된 객체의 S3 키 — confirm 요청 시 사용") val objectKey: String,
)
