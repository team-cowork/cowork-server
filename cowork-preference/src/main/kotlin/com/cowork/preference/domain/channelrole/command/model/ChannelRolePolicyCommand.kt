package com.cowork.preference.domain.channelrole.command.model

import io.vertx.core.json.JsonObject
import java.time.Instant

data class ChannelRolePolicyCommand(
    val operationId: String,
    val idempotencyKey: String,
    val requestHash: String,
    val commandType: ChannelRolePolicyCommandType,
    val teamId: Long,
    val channelId: Long,
    val roleId: Long,
    val actorId: Long,
    val actorMembershipVersion: Instant,
    val permissions: JsonObject?,
    val submittedAt: Instant,
)
