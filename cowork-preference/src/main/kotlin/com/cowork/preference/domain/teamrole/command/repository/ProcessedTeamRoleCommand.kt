package com.cowork.preference.domain.teamrole.command.repository

import io.vertx.core.json.JsonObject

data class ProcessedTeamRoleCommand(
    val operationId: String,
    val actorId: Long,
    val idempotencyKey: String,
    val requestHash: String,
    val commandType: String,
    val teamId: Long,
    val result: JsonObject,
)
