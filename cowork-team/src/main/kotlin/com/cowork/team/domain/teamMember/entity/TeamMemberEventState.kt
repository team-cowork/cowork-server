package com.cowork.team.domain.teamMember.entity

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.entity.nextTeamStateOccurredAt
import com.cowork.team.domain.teamRole.entity.TeamRole
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

data class TeamMemberEventStateId(var teamId: Long = 0, var userId: Long = 0) : Serializable

@Entity
@IdClass(TeamMemberEventStateId::class)
@Table(name = "tb_team_member_event_states")
class TeamMemberEventState(
    @Id
    @Column(name = "team_id", nullable = false)
    val teamId: Long,

    @Id
    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var role: TeamRole,

    @Column(name = "team_name", nullable = false, length = 100)
    var teamName: String,

    @Column(nullable = false)
    var deleted: Boolean,

    @Column(name = "state_occurred_at", nullable = false)
    var stateOccurredAt: Instant,
) {
    fun apply(team: Team, member: TeamMember, deleted: Boolean, requestedAt: Instant): Instant {
        require(team.id == teamId && member.team.id == teamId && member.userId == userId) {
            "Team member event state must use the same team and user ids"
        }
        val nextVersion = nextTeamStateOccurredAt(stateOccurredAt, requestedAt)
        role = member.role
        teamName = team.name
        this.deleted = deleted
        stateOccurredAt = nextVersion
        return nextVersion
    }

    companion object {
        fun create(team: Team, member: TeamMember, deleted: Boolean, requestedAt: Instant): TeamMemberEventState =
            TeamMemberEventState(
                teamId = team.id,
                userId = member.userId,
                role = member.role,
                teamName = team.name,
                deleted = deleted,
                stateOccurredAt = nextTeamStateOccurredAt(null, requestedAt),
            )
    }
}
