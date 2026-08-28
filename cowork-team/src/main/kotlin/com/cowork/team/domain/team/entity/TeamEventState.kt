package com.cowork.team.domain.team.entity

import com.cowork.team.global.projection.toProjectionPrecision
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.temporal.ChronoUnit

@Entity
@Table(name = "tb_team_event_states")
class TeamEventState(
    @Id
    @Column(name = "team_id", nullable = false)
    val teamId: Long,

    @Column(nullable = false, length = 100)
    var name: String,

    @Column(length = 500)
    var description: String?,

    @Column(name = "icon_url", length = 512)
    var iconUrl: String?,

    @Column(name = "owner_id", nullable = false)
    var ownerId: Long,

    @Column(name = "github_installation_id")
    var githubInstallationId: Long?,

    @Column(name = "github_org_login", length = 255)
    var githubOrgLogin: String?,

    @Column(name = "actor_user_id", nullable = false)
    var actorUserId: Long,

    @Column(nullable = false)
    var deleted: Boolean,

    @Column(name = "state_occurred_at", nullable = false)
    var stateOccurredAt: Instant,
) {
    fun apply(team: Team, actorUserId: Long, deleted: Boolean, requestedAt: Instant): Instant {
        require(team.id == teamId) { "Team event state must use the same team id" }
        val nextVersion = nextTeamStateOccurredAt(stateOccurredAt, requestedAt)
        name = team.name
        description = team.description
        iconUrl = team.iconUrl
        ownerId = team.ownerId
        githubInstallationId = team.githubInstallationId
        githubOrgLogin = team.githubOrgLogin
        this.actorUserId = actorUserId
        this.deleted = deleted
        stateOccurredAt = nextVersion
        return nextVersion
    }

    companion object {
        fun create(team: Team, actorUserId: Long, deleted: Boolean, requestedAt: Instant): TeamEventState =
            TeamEventState(
                teamId = team.id,
                name = team.name,
                description = team.description,
                iconUrl = team.iconUrl,
                ownerId = team.ownerId,
                githubInstallationId = team.githubInstallationId,
                githubOrgLogin = team.githubOrgLogin,
                actorUserId = actorUserId,
                deleted = deleted,
                stateOccurredAt = nextTeamStateOccurredAt(null, requestedAt),
            )
    }
}

internal fun nextTeamStateOccurredAt(current: Instant?, requestedAt: Instant): Instant {
    val requestedVersion = requestedAt.toProjectionPrecision()
    val minimumNextVersion = current?.toProjectionPrecision()?.plus(1, ChronoUnit.MICROS)
    return if (minimumNextVersion == null || requestedVersion.isAfter(minimumNextVersion)) {
        requestedVersion
    } else {
        minimumNextVersion
    }
}
