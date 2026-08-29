package com.cowork.channel.domain.channelRolePolicy.presentation.data.request

import io.swagger.v3.oas.annotations.media.Schema

data class UpsertChannelRolePolicyRequest(
    @param:Schema(
        description = "채널에서 역할에 적용할 권한. message_read boolean 하나만 허용됩니다.",
        example = "{\"message_read\":false}",
    )
    val permissions: Map<String, Boolean>,
)
