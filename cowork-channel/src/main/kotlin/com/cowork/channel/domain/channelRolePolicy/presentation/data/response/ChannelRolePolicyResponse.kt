package com.cowork.channel.domain.channelRolePolicy.presentation.data.response

import io.swagger.v3.oas.annotations.media.Schema

data class ChannelRolePolicyResponse(
    @field:Schema(description = "팀 커스텀 역할 ID")
    val roleId: Long,
    @field:Schema(description = "역할 우선순위")
    val priority: Int,
    @field:Schema(description = "채널에 명시된 권한")
    val permissions: Map<String, Boolean>,
)
