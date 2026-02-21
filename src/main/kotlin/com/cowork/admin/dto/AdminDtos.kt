package com.cowork.admin.dto

import com.cowork.user.domain.UserRole
import jakarta.validation.constraints.NotNull

data class AdminStatsResponse(
    val users: Long,
    val teams: Long,
    val projects: Long,
    val channels: Long,
    val messages: Long,
    val activeVoiceSessions: Long,
)

data class ChangeUserRoleRequest(
    @field:NotNull
    val role: UserRole,
)
