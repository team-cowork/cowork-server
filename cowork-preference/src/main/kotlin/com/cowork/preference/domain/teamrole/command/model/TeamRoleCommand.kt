package com.cowork.preference.domain.teamrole.command.model

import java.time.Instant

data class TeamRoleCommand(
    val operationId: String,
    val idempotencyKey: String,
    val requestHash: String,
    val commandType: TeamRoleCommandType,
    val teamId: Long,
    val actorId: Long,
    val actorMembershipVersion: Instant,
    val targetAccountId: Long?,
    val targetMembershipVersion: Instant?,
    val roleId: Long?,
    val role: TeamRoleCommandRoleInput?,
    val submittedAt: Instant,
)
