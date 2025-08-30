package com.cowork.team.dto

import com.cowork.team.domain.Team
import com.cowork.team.domain.TeamMemberRole
import com.cowork.team.domain.TeamVisibility
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

data class CreateTeamRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val name: String,
    @field:Size(max = 500)
    val description: String? = null,
    val visibility: TeamVisibility = TeamVisibility.PUBLIC,
)

data class UpdateTeamRequest(
    @field:Size(min = 1, max = 100)
    val name: String? = null,
    @field:Size(max = 500)
    val description: String? = null,
    val visibility: TeamVisibility? = null,
)

data class UpdateTeamIconRequest(
    @field:NotBlank
    val objectKey: String,
)

data class TeamResponse(
    val id: Long,
    val name: String,
    val description: String?,
    val iconUrl: String?,
    val visibility: TeamVisibility,
    val ownerId: Long,
    val memberCount: Long,
    val myRole: TeamMemberRole?,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun of(team: Team, iconUrl: String?, memberCount: Long, myRole: TeamMemberRole?) = TeamResponse(
            id = team.id,
            name = team.name,
            description = team.description,
            iconUrl = iconUrl,
            visibility = team.visibility,
            ownerId = team.ownerId,
            memberCount = memberCount,
            myRole = myRole,
            createdAt = team.createdAt,
        )
    }
}
