package com.cowork.channel.domain.channelRolePolicy.projection

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(
    name = "tb_team_role_assignment_projections",
    indexes = [
        Index(
            name = "idx_tb_team_role_assignment_projections_team_account",
            columnList = "team_id,account_id",
        ),
        Index(
            name = "idx_tb_team_role_assignment_projections_team_role",
            columnList = "team_id,role_id",
        ),
    ],
)
class TeamRoleAssignmentProjection(
    @Id
    @Column(name = "projection_key", nullable = false, length = 100)
    val projectionKey: String,

    @Column(name = "team_id", nullable = false)
    val teamId: Long,

    @Column(name = "account_id", nullable = false)
    val accountId: Long,

    @Column(name = "role_id", nullable = false)
    val roleId: Long,

    @Column(nullable = false)
    var deleted: Boolean = false,

    @Column(name = "source_occurred_at", nullable = false)
    var sourceOccurredAt: Instant,
) {
    fun markPresent(occurredAt: Instant) {
        deleted = false
        sourceOccurredAt = occurredAt
    }

    fun markDeleted(occurredAt: Instant) {
        deleted = true
        sourceOccurredAt = occurredAt
    }

    companion object {
        fun key(teamId: Long, accountId: Long, roleId: Long): String = "$teamId:$accountId:$roleId"
    }
}
