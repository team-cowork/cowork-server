package com.cowork.team.dto

import com.cowork.team.domain.TeamMember
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

data class TeamMemberResponse(
    @Schema(description = "멤버 레코드 ID", example = "1")
    val id: Long,
    @Schema(description = "사용자 ID", example = "42")
    val userId: Long,
    @Schema(description = "역할", example = "MEMBER", allowableValues = ["OWNER", "ADMIN", "MEMBER"])
    val role: String,
    @Schema(description = "가입 일시")
    val joinedAt: LocalDateTime,
) {
    companion object {
        fun of(member: TeamMember): TeamMemberResponse = TeamMemberResponse(
            id = member.id,
            userId = member.userId,
            role = member.role.name,
            joinedAt = member.joinedAt,
        )
    }
}
