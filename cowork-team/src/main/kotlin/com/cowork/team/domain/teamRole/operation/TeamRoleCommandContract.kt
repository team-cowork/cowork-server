package com.cowork.team.domain.teamRole.operation

import com.cowork.team.domain.teamRole.presentation.data.request.CreateTeamRoleRequest
import com.cowork.team.domain.teamRole.presentation.data.request.UpdateTeamRoleRequest
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

object TeamRoleContractTopics {
    const val COMMAND = "preference.team-role.command.v2"
    const val STATE = "preference.team-role.state.v2"
    const val RESULT = "preference.team-role.result.v2"
    const val SCHEMA_VERSION = 2
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
    val schemaVersion: Int = TeamRoleContractTopics.SCHEMA_VERSION,
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
    val permissions: Map<String, Boolean>? = null,
) {
    companion object {
        fun create(request: CreateTeamRoleRequest, permissions: Map<String, Boolean>) = TeamRoleCommandRoleInput(
            name = request.name,
            colorHex = request.colorHex,
            priority = request.priority,
            mentionable = request.mentionable,
            permissions = permissions,
        )

        fun update(request: UpdateTeamRoleRequest, permissions: Map<String, Boolean>?) = TeamRoleCommandRoleInput(
            name = request.name,
            colorHex = request.colorHex,
            priority = request.priority,
            mentionable = request.mentionable,
            permissions = permissions,
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
    val permissions: Map<String, Boolean>,
    val createdAt: String?,
    val updatedAt: String?,
    // TODO(#596): 2026-11-01 05:00 KST 이후 아래 조건 완료를 확인하고 제거한다.
    // 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
    // producer 전환·저장 결과 보정 및 구형 결과 재처리 종료 후 isDefault 누락 기본값을 제거한다.
    @field:JsonProperty("isDefault")
    val isDefault: Boolean = false,
)

data class TeamRoleCommandError(val code: String, val message: String)

data class TeamRoleProjectionExpectation(val key: String, val occurredAt: Instant, val deleted: Boolean)
