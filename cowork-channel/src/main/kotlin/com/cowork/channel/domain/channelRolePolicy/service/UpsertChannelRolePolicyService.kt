package com.cowork.channel.domain.channelRolePolicy.service

import com.cowork.channel.domain.channelRolePolicy.presentation.data.request.UpsertChannelRolePolicyRequest
import com.cowork.channel.domain.channelRolePolicy.presentation.data.response.ChannelRolePolicyOperationResponse

interface UpsertChannelRolePolicyService {
    fun execute(
        actorId: Long,
        channelId: Long,
        roleId: Long,
        idempotencyKey: String,
        request: UpsertChannelRolePolicyRequest,
    ): ChannelRolePolicyOperationResponse
}
