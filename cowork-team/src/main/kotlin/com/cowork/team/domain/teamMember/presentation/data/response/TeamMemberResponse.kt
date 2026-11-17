package com.cowork.team.domain.teamMember.presentation.data.response

import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

data class TeamMemberResponse(
    @field:Schema(description = "멤버 레코드 ID", example = "1")
    val id: Long,
    @field:Schema(description = "사용자 ID", example = "42")
    val userId: Long,
    // TODO(2026-11-17T06:59:44Z): Remove this legacy label after clients use assigned role policies and ownerId.
    @field:Schema(description = "이전 멤버십 표시값. 권한은 역할 정책으로 판정합니다.", deprecated = true)
    val role: String,
    @field:Schema(description = "팀 닉네임. null이면 계정 닉네임을 사용합니다.", example = "홍길동 / 프론트엔드")
    val nickname: String?,
    @field:Schema(description = "커스텀 역할 목록")
    val roles: List<TeamRoleResponse> = emptyList(),
    @field:Schema(description = "가입 일시")
    val joinedAt: LocalDateTime,
) {
    companion object {
        fun of(member: TeamMember, roles: List<TeamRoleResponse> = emptyList()): TeamMemberResponse =
            TeamMemberResponse(
                id = member.id,
                userId = member.userId,
                role = member.role.name,
                nickname = member.nickname,
                roles = roles,
                joinedAt = requireNotNull(member.joinedAt),
            )
    }
}
