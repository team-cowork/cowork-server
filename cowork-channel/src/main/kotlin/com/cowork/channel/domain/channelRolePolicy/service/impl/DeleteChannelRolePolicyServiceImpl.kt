package com.cowork.channel.domain.channelRolePolicy.service.impl

import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.support.ChannelPermissionSupport
import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyCommandSubmission
import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyCommandType
import com.cowork.channel.domain.channelRolePolicy.presentation.data.response.ChannelRolePolicyOperationResponse
import com.cowork.channel.domain.channelRolePolicy.service.DeleteChannelRolePolicyService
import com.cowork.channel.domain.channelRolePolicy.service.support.ChannelRolePolicyAccessSupport
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DeleteChannelRolePolicyServiceImpl(
    private val channelAccessGuard: ChannelAccessGuard,
    private val channelPermissionSupport: ChannelPermissionSupport,
    private val channelRolePolicyAccessSupport: ChannelRolePolicyAccessSupport,
    private val channelRolePolicyCommandSubmission: ChannelRolePolicyCommandSubmission,
) : DeleteChannelRolePolicyService {
    @Transactional
    override fun execute(
        actorId: Long,
        channelId: Long,
        roleId: Long,
        idempotencyKey: String,
    ): ChannelRolePolicyOperationResponse {
        val channel = channelAccessGuard.findChannelForUpdateOrThrow(channelId)
        val teamId = channelAccessGuard.requireTeamChannel(channel)
        return channelRolePolicyCommandSubmission.submit(
            idempotencyKey = idempotencyKey,
            commandType = ChannelRolePolicyCommandType.DELETE,
            teamId = teamId,
            channelId = channelId,
            roleId = roleId,
            actorId = actorId,
            permissions = null,
        ) {
            channelPermissionSupport.requireRolePolicyManager(channel, actorId, roleId, null)
            channelRolePolicyAccessSupport.requireRole(teamId, roleId)
            channelRolePolicyAccessSupport.requirePolicy(teamId, channelId, roleId)
        }
    }
}
