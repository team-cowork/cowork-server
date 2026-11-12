package com.cowork.preference.domain.teamrole.model

import java.time.Instant

data class TeamRoleAssignmentState(
    val teamId: Long,
    val accountId: Long,
    val roleId: Long,
    val assignment: AccountTeamRole?,
    val stateOccurredAt: Instant,
)
