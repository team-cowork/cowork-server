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
    @field:Schema(description = "닉네임 형식 안내와 팀 닉네임 설정 유도 여부", example = "true")
    val nicknameFormatEnforced: Boolean,
    @field:Schema(description = "닉네임 형식 안내에 보여 줄 예시", example = "홍길동 / 프론트엔드")
    val nicknameFormatExample: String?,
)
