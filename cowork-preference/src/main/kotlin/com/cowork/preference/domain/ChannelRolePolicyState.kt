package com.cowork.preference.domain

import io.vertx.core.json.JsonObject
import java.time.Instant

data class ChannelRolePolicyState(
    val teamId: Long,
    val channelId: Long,
    val roleId: Long,
    val permissions: JsonObject?,
    val stateOccurredAt: Instant,
)
