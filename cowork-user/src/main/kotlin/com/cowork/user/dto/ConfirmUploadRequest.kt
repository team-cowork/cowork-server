package com.cowork.user.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "프로필 이미지 업로드 확인 요청")
data class ConfirmUploadRequest(
    @Schema(description = "S3에 업로드된 객체 키 (PresignedUrlResponse.objectKey 값)") val objectKey: String,
)
