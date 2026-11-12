package com.cowork.preference.domain.channelrole.command.model

data class ChannelRolePolicyCommandEnvelope(
    val operationId: String,
    val idempotencyKey: String,
    val requestHash: String,
    val commandType: ChannelRolePolicyCommandType,
    val teamId: Long,
    val channelId: Long,
    val roleId: Long,
)
