package com.cowork.project.dto

import io.swagger.v3.oas.annotations.media.Schema

data class UpdateProjectMemberRoleRequest(
    @Schema(description = "변경할 역할", example = "VIEWER", allowableValues = ["OWNER", "EDITOR", "VIEWER"], required = true)
    val role: String,
)
