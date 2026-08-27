package com.cowork.team.domain.teamRole.operation

import com.cowork.team.domain.teamRole.presentation.data.request.CreateTeamRoleRequest
import com.cowork.team.domain.teamRole.presentation.data.request.UpdateTeamRoleRequest
import java.time.Instant

object TeamRoleContractTopics {
    const val COMMAND = "preference.team-role.command"
    const val STATE = "preference.team-role.changed"
    const val RESULT = "preference.team-role.command-result"
}

enum class TeamRoleCommandType {
    CREATE,
    UPDATE,
    DELETE,
    ASSIGN,
    REVOKE,
}

enum class TeamRoleOperationStatus {
    PENDING,
    PROCESSING,
    SUCCEEDED,
    FAILED,
}

data class TeamRoleCommandPayload(
    val schemaVersion: Int = 1,
    val operationId: String,
    val idempotencyKey: String,
    val requestHash: String,
    val commandType: TeamRoleCommandType,
    val teamId: Long,
    val actorId: Long,
    val actorMembershipVersion: Instant,
    val targetAccountId: Long? = null,
    val targetMembershipVersion: Instant? = null,
    val roleId: Long? = null,
    val role: TeamRoleCommandRoleInput? = null,
    val submittedAt: Instant,
)

data class TeamRoleCommandRoleInput(
    val name: String? = null,
    val colorHex: String? = null,
    val priority: Int? = null,
    val mentionable: Boolean? = null,
    val permissions: Set<String>? = null,
) {
    companion object {
        fun create(request: CreateTeamRoleRequest) = TeamRoleCommandRoleInput(
            name = request.name,
            colorHex = request.colorHex,
            priority = request.priority,
            mentionable = request.mentionable,
            permissions = request.permissions,
        )

        fun update(request: UpdateTeamRoleRequest) = TeamRoleCommandRoleInput(
            name = request.name,
            colorHex = request.colorHex,
            priority = request.priority,
            mentionable = request.mentionable,
            permissions = request.permissions,
        )
    }
}

data class TeamRoleCommandResultPayload(
    val schemaVersion: Int,
    val operationId: String,
    val idempotencyKey: String,
    val requestHash: String,
    val commandType: TeamRoleCommandType,
    val teamId: Long,
    val status: TeamRoleOperationStatus,
    val role: TeamRoleResultRole? = null,
    val error: TeamRoleCommandError? = null,
    val projection: TeamRoleProjectionExpectation? = null,
    val occurredAt: Instant,
)

data class TeamRoleResultRole(
    val id: Long,
    val teamId: Long,
    val name: String,
    val colorHex: String,
    val priority: Int,
    val mentionable: Boolean,
    val permissions: Set<String>,
    val createdAt: String?,
    val updatedAt: String?,
)

data class TeamRoleCommandError(val code: String, val message: String)

data class TeamRoleProjectionExpectation(val key: String, val occurredAt: Instant, val deleted: Boolean)
