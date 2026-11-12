package com.cowork.preference.domain.teamrole.command.model

sealed interface TeamRoleCommandRecordDecision {
    data class Apply(val command: TeamRoleCommand) : TeamRoleCommandRecordDecision
    data class Reject(val envelope: TeamRoleCommandEnvelope, val reason: String) : TeamRoleCommandRecordDecision
    data class Quarantine(val reason: String) : TeamRoleCommandRecordDecision
}
