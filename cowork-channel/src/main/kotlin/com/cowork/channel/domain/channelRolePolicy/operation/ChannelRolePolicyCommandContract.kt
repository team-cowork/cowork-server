package com.cowork.channel.domain.channelRolePolicy.operation

import com.cowork.channel.global.consumer.Topics
import java.time.Instant

object ChannelRolePolicyContractTopics {
    const val COMMAND = Topics.CHANNEL_ROLE_POLICY_COMMAND
    const val STATE = Topics.CHANNEL_ROLE_POLICY_CHANGED
    const val RESULT = Topics.CHANNEL_ROLE_POLICY_COMMAND_RESULT
}

enum class ChannelRolePolicyCommandType {
    UPSERT,
    DELETE,
}

enum class ChannelRolePolicyOperationStatus {
    PENDING,
    PROCESSING,
    SUCCEEDED,
    FAILED,
}

data class ChannelRolePolicyCommandPayload(
    val schemaVersion: Int = 1,
    val operationId: String,
    val idempotencyKey: String,
    val requestHash: String,
    val commandType: ChannelRolePolicyCommandType,
    val teamId: Long,
    val channelId: Long,
    val roleId: Long,
    val actorId: Long,
    val actorMembershipVersion: Instant,
    val permissions: Map<String, Boolean>? = null,
    val submittedAt: Instant,
)

data class ChannelRolePolicyCommandResultPayload(
    val schemaVersion: Int,
    val operationId: String,
    val idempotencyKey: String,
    val requestHash: String,
    val commandType: ChannelRolePolicyCommandType,
    val teamId: Long,
    val channelId: Long,
    val roleId: Long,
    val status: ChannelRolePolicyOperationStatus,
    val permissions: Map<String, Boolean>? = null,
    val error: ChannelRolePolicyCommandError? = null,
    val projection: ChannelRolePolicyProjectionExpectation? = null,
    val occurredAt: Instant,
)

data class ChannelRolePolicyCommandError(val code: String, val message: String)

data class ChannelRolePolicyProjectionExpectation(val key: String, val occurredAt: Instant, val deleted: Boolean)
