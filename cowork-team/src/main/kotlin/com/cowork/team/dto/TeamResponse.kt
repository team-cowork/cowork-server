package com.cowork.team.dto

import com.cowork.team.domain.Team
import java.time.LocalDateTime

data class TeamResponse(
    val id: Long,
    val name: String,
    val description: String?,
    val iconUrl: String?,
    val ownerId: Long,
    val createdAt: LocalDateTime,
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