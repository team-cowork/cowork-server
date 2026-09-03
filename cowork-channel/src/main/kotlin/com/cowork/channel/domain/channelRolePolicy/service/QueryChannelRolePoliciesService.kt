package com.cowork.channel.domain.channelRolePolicy.service

import com.cowork.channel.domain.channelRolePolicy.presentation.data.response.ChannelRolePolicyResponse

interface QueryChannelRolePoliciesService {
    fun execute(actorId: Long, channelId: Long): List<ChannelRolePolicyResponse>
}
