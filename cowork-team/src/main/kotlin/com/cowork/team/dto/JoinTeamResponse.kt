package com.cowork.team.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

data class JoinTeamResponse(
    @Schema(description = "팀 ID", example = "1")
    val teamId: Long,
    @Schema(description = "가입한 사용자 ID", example = "99")
    val userId: Long,
    @Schema(description = "부여된 역할", example = "MEMBER")
    val role: String,
    @Schema(description = "가입 일시")
    val joinedAt: LocalDateTime,
)
