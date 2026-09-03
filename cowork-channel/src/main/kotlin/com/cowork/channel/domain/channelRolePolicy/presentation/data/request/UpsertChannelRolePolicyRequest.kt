package com.cowork.channel.domain.channelRolePolicy.presentation.data.request

import io.swagger.v3.oas.annotations.media.Schema

data class UpsertChannelRolePolicyRequest(
    @param:Schema(
        description = "채널에서 역할에 적용할 권한. 알 수 없는 키는 무시되고 누락된 키는 기본값으로 채워집니다.",
        example = "{\"message_read\":false}",
    )
    val permissions: Map<String, Any?>,
)
