package com.cowork.team.domain.teamRole.presentation.data.response

import com.cowork.team.domain.teamRole.service.support.TeamRolePermissionGroup
import io.swagger.v3.oas.annotations.media.Schema

data class TeamRolePermissionGroupResponse(
    @field:Schema(description = "권한 그룹 키", example = "TEXT_CHANNEL")
    val key: TeamRolePermissionGroup,
    @field:Schema(description = "권한 그룹 제목", example = "텍스트 채널")
    val title: String,
    @field:Schema(description = "그룹에 속한 권한. 화면 표시 순서입니다.")
    val permissions: List<TeamRolePermissionResponse>,
)
