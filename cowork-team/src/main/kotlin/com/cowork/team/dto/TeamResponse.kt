package com.cowork.team.dto

import com.cowork.team.domain.Team
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

data class TeamResponse(
    @Schema(description = "팀 ID", example = "1")
    val id: Long,
    @Schema(description = "팀 이름", example = "코워크팀")
    val name: String,
    @Schema(description = "팀 설명", example = "협업 서비스 개발팀")
    val description: String?,
    @Schema(description = "팀 아이콘 URL", example = "https://example.com/icon.png")
    val iconUrl: String?,
    @Schema(description = "팀 소유자 ID", example = "1")
    val ownerId: Long,
    @Schema(description = "생성 일시")
    val createdAt: LocalDateTime,
    @Schema(description = "수정 일시")
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun of(team: Team): TeamResponse = TeamResponse(
            id = team.id,
            name = team.name,
            description = team.description,
            iconUrl = team.iconUrl,
            ownerId = team.ownerId,
            createdAt = team.createdAt,
            updatedAt = team.updatedAt,
        )
    }
}