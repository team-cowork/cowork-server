package com.cowork.team.domain.team.presentation.data.response

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

data class JoinTeamResponse(
    @field:Schema(description = "팀 ID", example = "1")
    val teamId: Long,
    @field:Schema(description = "가입한 사용자 ID", example = "99")
    val userId: Long,
    @field:Schema(description = "부여된 역할", example = "MEMBER")
    val role: String,
    @field:Schema(description = "가입 일시")
    val joinedAt: LocalDateTime,
)
