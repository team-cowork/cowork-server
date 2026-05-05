package com.cowork.project.dto

import io.swagger.v3.oas.annotations.media.Schema

data class AddProjectMemberRequest(
    @Schema(description = "초대할 사용자 ID", example = "2", required = true)
    val userId: Long,
    @Schema(description = "부여할 역할", example = "EDITOR", allowableValues = ["OWNER", "EDITOR", "VIEWER"], required = true)
    val role: String,
)
