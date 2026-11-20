package com.cowork.team.domain.teamMember

import java.time.LocalDateTime

data class RegisteredTeamMember(
    val teamId: Long,
    val userId: Long,
    val role: String,
    val joinedAt: LocalDateTime,
    val nicknameFormatEnforced: Boolean,
    val nicknameFormatExample: String?,
)
