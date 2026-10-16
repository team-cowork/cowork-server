package com.cowork.channel.domain.channelRolePolicy.presentation.data.request

import io.swagger.v3.oas.annotations.media.Schema

data class UpsertChannelRolePolicyRequest(
    @param:Schema(
        description = "채널에서 역할에 덮어쓸 권한. true는 허용, false는 거부이며 " +
            "누락된 키는 역할 기본 권한을 따릅니다. 알 수 없는 키는 무시됩니다.",
        example = "{\"message_read\":true,\"message_send\":false}",
    )
    val permissions: Map<String, Any?>,
)
