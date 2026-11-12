package com.cowork.preference.domain.teamrole.command.model

data class TeamRoleCommandEnvelope(
    val operationId: String,
    val idempotencyKey: String,
    val requestHash: String,
    val commandType: TeamRoleCommandType,
    val teamId: Long,
)
