package com.cowork.preference.domain.teamrole.model

import java.time.Instant

data class TeamRoleState(
    val teamId: Long,
    val roleId: Long,
    val role: TeamRoleDefinition?,
    val stateOccurredAt: Instant,
)
