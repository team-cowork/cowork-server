package com.cowork.team.domain.teamRole.projection

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(
    name = "tb_team_role_projections",
    indexes = [Index(name = "idx_tb_team_role_projections_team_id", columnList = "team_id")],
)
class TeamRoleProjection(
    @Id
    @Column(name = "role_id", nullable = false)
    val roleId: Long,

    @Column(name = "team_id", nullable = false)
    val teamId: Long,

    @Column(nullable = false, length = 100)
    var name: String = "",

    @Column(name = "color_hex", nullable = false, length = 7)
    var colorHex: String = "#000000",

    @Column(nullable = false)
    var priority: Int = 0,

    @Column(nullable = false)
    var mentionable: Boolean = false,

    @Column(name = "permissions_json", nullable = false, columnDefinition = "TEXT")
    var permissionsJson: String = "{}",

    @Column(nullable = false)
    var deleted: Boolean = false,

    @Column(name = "source_created_at", nullable = false)
    val sourceCreatedAt: Instant,

    @Column(name = "source_occurred_at", nullable = false)
    var sourceOccurredAt: Instant,

    @Column(name = "is_default", nullable = false)
    var isDefault: Boolean = false,
) {
    fun applyUpsert(
        name: String,
        colorHex: String,
        priority: Int,
        mentionable: Boolean,
        permissionsJson: String,
        occurredAt: Instant,
        isDefault: Boolean = false,
    ) {
        // 역할 ID의 기본 역할 여부는 생성 후 바뀌지 않는다.
        this.isDefault = this.isDefault || isDefault
        this.name = name
        this.colorHex = colorHex
        this.priority = priority
        this.mentionable = mentionable
        this.permissionsJson = permissionsJson
        this.deleted = false
        this.sourceOccurredAt = occurredAt
    }

    fun markDeleted(occurredAt: Instant) {
        deleted = true
        sourceOccurredAt = occurredAt
    }
}
