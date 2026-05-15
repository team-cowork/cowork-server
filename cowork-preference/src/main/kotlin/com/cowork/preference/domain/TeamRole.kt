package com.cowork.preference.domain

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
)
