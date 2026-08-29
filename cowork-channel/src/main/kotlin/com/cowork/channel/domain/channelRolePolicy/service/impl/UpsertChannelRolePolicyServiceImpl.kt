package com.cowork.channel.domain.channelRolePolicy.service.impl

import com.cowork.channel.domain.channel.service.ChannelAccessGuard
import com.cowork.channel.domain.channel.service.support.ChannelPermissionSupport
import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyCommandSubmission
import com.cowork.channel.domain.channelRolePolicy.operation.ChannelRolePolicyCommandType
import com.cowork.channel.domain.channelRolePolicy.presentation.data.request.UpsertChannelRolePolicyRequest
import com.cowork.channel.domain.channelRolePolicy.presentation.data.response.ChannelRolePolicyOperationResponse
import com.cowork.channel.domain.channelRolePolicy.service.UpsertChannelRolePolicyService
import com.cowork.channel.domain.channelRolePolicy.service.support.ChannelRolePolicyAccessSupport
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class UpsertChannelRolePolicyServiceImpl(
    private val channelAccessGuard: ChannelAccessGuard,
    private val channelPermissionSupport: ChannelPermissionSupport,
    private val policyAccessSupport: ChannelRolePolicyAccessSupport,
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
        if (
            request.permissions.keys != setOf(MESSAGE_READ_KEY) ||
            request.permissions[MESSAGE_READ_KEY] == null
        ) {
            throw ExpectedException(
                "permissions에는 message_read boolean 하나만 지정해야 합니다.",
                HttpStatus.BAD_REQUEST,
            )
        }
        val channel = channelAccessGuard.findChannelForUpdateOrThrow(channelId)
        val teamId = channelAccessGuard.requireTeamChannel(channel)
        return commandSubmission.submit(
            idempotencyKey = idempotencyKey,
            commandType = ChannelRolePolicyCommandType.UPSERT,
            teamId = teamId,
            channelId = channelId,
            roleId = roleId,
            actorId = actorId,
            permissions = request.permissions,
        ) {
            channelPermissionSupport.requireChannelManager(channel, actorId)
            policyAccessSupport.requireRole(teamId, roleId)
        }
    }

    private companion object {
        const val MESSAGE_READ_KEY = "message_read"
    }
}
