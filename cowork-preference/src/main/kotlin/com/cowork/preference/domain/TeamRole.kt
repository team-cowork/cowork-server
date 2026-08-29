package com.cowork.preference.domain

import java.time.Instant
import java.time.OffsetDateTime

data class TeamRoleDefinition(
    val id: Long,
    val teamId: Long,
    val name: String,
    val colorHex: String,
    val priority: Int,
    val mentionable: Boolean,
    val permissions: Set<String>,
    val createdAt: OffsetDateTime?,
    val updatedAt: OffsetDateTime?,
)

data class AccountTeamRole(
    val accountId: Long,
    val teamId: Long,
    val roleId: Long,
    val sourceMembershipVersion: Instant? = null,
    val updatedAt: OffsetDateTime? = null,
)

data class TeamRoleState(
    val teamId: Long,
    val roleId: Long,
    val role: TeamRoleDefinition?,
    val stateOccurredAt: Instant,
)

data class TeamRoleAssignmentState(
    val teamId: Long,
    val accountId: Long,
    val roleId: Long,
    val assignment: AccountTeamRole?,
    val stateOccurredAt: Instant,
)

data class TeamRoleMemberFence(val teamId: Long, val accountId: Long, val stateOccurredAt: Instant)
