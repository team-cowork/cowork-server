package com.cowork.team.domain.teamRole.presentation.data.response

import com.cowork.team.domain.teamRole.service.support.TeamRolePermission
import com.cowork.team.domain.teamRole.service.support.TeamRolePermissionChannelType
import io.swagger.v3.oas.annotations.media.Schema

data class TeamRolePermissionResponse(
    @field:Schema(description = "권한 키. 역할과 채널별 역할 권한의 permissions 키로 사용합니다.", example = "message_send")
    val key: String,
    @field:Schema(description = "권한 제목", example = "메시지 보내기")
    val title: String,
    @field:Schema(description = "권한 설명", example = "채널에 메시지를 보낼 수 있습니다.")
    val description: String,
    @field:Schema(description = "채널별 역할 권한으로 덮어쓸 수 있는 채널 종류. 비어 있으면 팀 전체에만 적용됩니다.", example = "[\"TEXT\"]")
    val channelTypes: List<TeamRolePermissionChannelType>,
) {
    companion object {
        fun of(permission: TeamRolePermission) = TeamRolePermissionResponse(
            key = permission.key,
            title = permission.title,
            description = permission.description,
            channelTypes = permission.channelTypes.toList(),
        )
    }
}
