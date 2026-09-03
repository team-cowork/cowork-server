package com.cowork.channel.domain.channelRolePolicy.service

import com.cowork.channel.domain.channelRolePolicy.presentation.data.response.ChannelRolePolicyOperationResponse

interface DeleteChannelRolePolicyService {
    fun execute(
        actorId: Long,
        channelId: Long,
        roleId: Long,
        idempotencyKey: String,
    ): ChannelRolePolicyOperationResponse
}
