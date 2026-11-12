package com.cowork.preference.domain.teamrole.model

import java.time.OffsetDateTime

data class TeamRoleDefinition(
    val id: Long,
    val teamId: Long,
    val name: String,
    val colorHex: String,
    val priority: Int,
    val mentionable: Boolean,
    val permissions: Map<String, Boolean>,
    val createdAt: OffsetDateTime?,
    val updatedAt: OffsetDateTime?,
    val isDefault: Boolean = false,
)
