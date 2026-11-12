package com.cowork.preference.domain.teamrole.command.model

data class TeamRoleCommandQuarantineRecord(
    val topic: String,
    val partition: Int,
    val offset: Long,
    val key: String?,
    val payload: String?,
    val reason: String,
)
