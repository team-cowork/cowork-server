package com.cowork.preference.domain.teamrole.command.repository

data class ProcessedTeamRoleCommandMatches(
    val byOperationId: ProcessedTeamRoleCommand?,
    val byActorAndIdempotencyKey: ProcessedTeamRoleCommand?,
)
