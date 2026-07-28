package com.cowork.project.domain.projectMember.presentation.data.request

import com.cowork.project.domain.projectMember.entity.ProjectMemberRole
import io.swagger.v3.oas.annotations.media.Schema

data class AddProjectMemberReqDto(
    @Schema(description = "초대할 사용자 ID", example = "2", required = true)
    val userId: Long,
    @Schema(
        description = "부여할 역할",
        example = "EDITOR",
        required = true,
    )
    val role: ProjectMemberRole,
)
