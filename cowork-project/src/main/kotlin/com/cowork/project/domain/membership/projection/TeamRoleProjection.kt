package com.cowork.project.domain.membership.projection

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

    @Column(name = "permissions_json", columnDefinition = "TEXT")
    var permissionsJson: String? = null,

    @Column(name = "is_default", nullable = false)
    var isDefault: Boolean = false,

    @Column(nullable = false)
    var deleted: Boolean = false,

    @Column(name = "source_occurred_at", nullable = false)
    var sourceOccurredAt: Instant,
) {
    fun applyUpsert(priority: Int, occurredAt: Instant, permissionsJson: String, isDefault: Boolean = false) {
        this.isDefault = isDefault
        this.priority = priority
        this.permissionsJson = permissionsJson
        deleted = false
        sourceOccurredAt = occurredAt
    }

    fun markDeleted(occurredAt: Instant) {
        deleted = true
        sourceOccurredAt = occurredAt
    }
}
