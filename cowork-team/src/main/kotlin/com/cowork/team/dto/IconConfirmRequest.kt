package com.cowork.team.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "팀 아이콘 업로드 확인 요청")
data class IconConfirmRequest(
    @Schema(description = "S3에 업로드된 객체 키 (IconPresignedUrlResponse.objectKey 값)") val objectKey: String,
)
