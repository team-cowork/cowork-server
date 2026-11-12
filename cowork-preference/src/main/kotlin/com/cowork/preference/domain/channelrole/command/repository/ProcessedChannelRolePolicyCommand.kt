package com.cowork.preference.domain.channelrole.command.repository

import com.cowork.preference.domain.channelrole.command.model.ChannelRolePolicyCommandType
import io.vertx.core.json.JsonObject
import java.time.Instant

data class ProcessedChannelRolePolicyCommand(
    val operationId: String,
    val actorId: Long,
    val idempotencyKey: String,
    val requestHash: String,
    val commandType: ChannelRolePolicyCommandType,
    val teamId: Long,
    val channelId: Long,
    val roleId: Long,
    val actorMembershipVersion: Instant,
    val permissions: JsonObject?,
    val result: JsonObject,
)
