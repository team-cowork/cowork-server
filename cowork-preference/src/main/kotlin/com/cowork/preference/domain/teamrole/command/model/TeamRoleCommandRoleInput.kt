package com.cowork.preference.domain.teamrole.command.model

data class TeamRoleCommandRoleInput(
    val name: String?,
    val colorHex: String?,
    val priority: Int?,
    val mentionable: Boolean?,
    val permissions: Map<String, Boolean>?,
)
