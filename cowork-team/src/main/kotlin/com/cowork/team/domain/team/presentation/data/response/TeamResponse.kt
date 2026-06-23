package com.cowork.team.domain.team.presentation.data.response

import com.cowork.team.domain.team.entity.Team
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

data class TeamResponse(
    @field:Schema(description = "팀 ID", example = "1")
    val id: Long,
    @field:Schema(description = "팀 이름", example = "코워크팀")
    val name: String,
    @field:Schema(description = "팀 설명", example = "협업 서비스 개발팀")
    val description: String?,
    @field:Schema(description = "팀 아이콘 URL", example = "https://example.com/icon.png")
    val iconUrl: String?,
    @field:Schema(description = "팀 소유자 ID", example = "1")
    val ownerId: Long,
    @field:Schema(description = "생성 일시")
    val createdAt: LocalDateTime,
    @field:Schema(description = "수정 일시")
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun of(team: Team): TeamResponse = TeamResponse(
            id = team.id,
            name = team.name,
            description = team.description,
            iconUrl = team.iconUrl,
            ownerId = team.ownerId,
            createdAt = requireNotNull(team.createdAt),
            updatedAt = requireNotNull(team.updatedAt),
        )
    }
}
