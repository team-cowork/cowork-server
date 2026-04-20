package com.cowork.user.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "내 프로필 수정 요청")
data class UpdateMyProfileRequest(
    @Schema(description = "닉네임") val nickname: String?,
    @Schema(description = "자기소개") val description: String?,
    @Schema(description = "역할 목록 (예: [\"FRONTEND\", \"BACKEND\"])") val roles: List<String>?,
)
