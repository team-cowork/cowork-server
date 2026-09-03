package com.cowork.channel.domain.channelRolePolicy.projection

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(
    name = "tb_team_role_projections",
    indexes = [Index(name = "idx_tb_team_role_projections_team_priority", columnList = "team_id,priority")],
)
class TeamRoleProjection(
    @Id
    @Column(name = "role_id", nullable = false)
    val roleId: Long,

    @Column(name = "team_id", nullable = false)
    val teamId: Long,

    @Column(nullable = false)
    var priority: Int = 0,

    @Column(nullable = false)
    var deleted: Boolean = false,

    @Column(name = "source_occurred_at", nullable = false)
    var sourceOccurredAt: Instant,
) {
    fun applyUpsert(priority: Int, occurredAt: Instant) {
        this.priority = priority
        deleted = false
        sourceOccurredAt = occurredAt
    }

    fun markDeleted(occurredAt: Instant) {
        deleted = true
        sourceOccurredAt = occurredAt
    }
}
