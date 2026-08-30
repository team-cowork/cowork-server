package com.cowork.channel.domain.channelRolePolicy.service.impl

import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.support.ChannelPermissionSupport
import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyCommandSubmission
import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyCommandType
import com.cowork.channel.domain.channelRolePolicy.presentation.data.request.UpsertChannelRolePolicyRequest
import com.cowork.channel.domain.channelRolePolicy.presentation.data.response.ChannelRolePolicyOperationResponse
import com.cowork.channel.domain.channelRolePolicy.service.UpsertChannelRolePolicyService
import com.cowork.channel.domain.channelRolePolicy.service.support.ChannelRolePolicyAccessSupport
import com.cowork.channel.domain.channelRolePolicy.service.support.ChannelRolePolicyPermissionSchema
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UpsertChannelRolePolicyServiceImpl(
    private val channelAccessGuard: ChannelAccessGuard,
    private val channelPermissionSupport: ChannelPermissionSupport,
    private val policyAccessSupport: ChannelRolePolicyAccessSupport,
    private val permissionSchema: ChannelRolePolicyPermissionSchema,
    private val commandSubmission: ChannelRolePolicyCommandSubmission,
) : UpsertChannelRolePolicyService {
    @Transactional
    override fun execute(
        actorId: Long,
        channelId: Long,
        roleId: Long,
        idempotencyKey: String,
        request: UpsertChannelRolePolicyRequest,
    ): ChannelRolePolicyOperationResponse {
        val permissions = permissionSchema.normalize(request.permissions)
        val channel = channelAccessGuard.findChannelForUpdateOrThrow(channelId)
        val teamId = channelAccessGuard.requireTeamChannel(channel)
        return commandSubmission.submit(
            idempotencyKey = idempotencyKey,
            commandType = ChannelRolePolicyCommandType.UPSERT,
            teamId = teamId,
            channelId = channelId,
            roleId = roleId,
            actorId = actorId,
            permissions = permissions,
        ) {
            channelPermissionSupport.requireChannelManager(channel, actorId)
            policyAccessSupport.requireRole(teamId, roleId)
        }
    }
}
