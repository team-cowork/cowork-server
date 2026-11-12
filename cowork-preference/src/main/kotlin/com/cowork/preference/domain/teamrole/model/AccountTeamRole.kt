package com.cowork.preference.domain.teamrole.model

import java.time.Instant
import java.time.OffsetDateTime

data class AccountTeamRole(
    val accountId: Long,
    val teamId: Long,
    val roleId: Long,
    val sourceMembershipVersion: Instant? = null,
    val updatedAt: OffsetDateTime? = null,
)
