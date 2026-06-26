package com.cowork.project.domain.projectMember.presentation.data.request

import io.swagger.v3.oas.annotations.media.Schema

data class UpdateProjectMemberRoleReqDto(
    @Schema(description = "변경할 역할", example = "VIEWER", allowableValues = ["OWNER", "EDITOR", "VIEWER"], required = true)
    val role: String,
)
