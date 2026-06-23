package com.cowork.team.domain.teamInvite.presentation.data.response

import com.cowork.team.domain.teamInvite.entity.TeamInvite
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

data class InviteResponse(
    @field:Schema(description = "초대 코드", example = "aB3xK9mZ")
    val inviteCode: String,
    @field:Schema(description = "팀 ID", example = "1")
    val teamId: Long,
    @field:Schema(description = "생성자 사용자 ID", example = "42")
    val createdBy: Long,
    @field:Schema(description = "유효 기간", example = "7d", allowableValues = ["1d", "7d", "30d", "never"])
    val duration: String,
    @field:Schema(description = "만료 일시 (never이면 null)")
    val expiresAt: LocalDateTime?,
    @field:Schema(description = "만료 또는 삭제 여부")
    val expired: Boolean,
    @field:Schema(description = "생성 일시")
    val createdAt: LocalDateTime,
) {
    companion object {
        fun of(invite: TeamInvite): InviteResponse = InviteResponse(
            inviteCode = invite.inviteCode,
            teamId = invite.team.id,
            createdBy = invite.createdBy,
            duration = invite.duration,
            expiresAt = invite.expiresAt,
            expired = invite.isDeleted() || invite.isExpired(),
            createdAt = requireNotNull(invite.createdAt),
        )
    }
}
