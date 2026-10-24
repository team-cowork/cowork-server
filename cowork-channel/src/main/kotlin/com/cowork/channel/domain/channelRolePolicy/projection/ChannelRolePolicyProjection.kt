package com.cowork.channel.domain.channelRolePolicy.projection

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(
    name = "tb_channel_role_policy_projections",
    indexes = [
        Index(
            name = "idx_tb_channel_role_policy_projections_channel_role",
            columnList = "channel_id,role_id",
        ),
        Index(
            name = "idx_tb_channel_role_policy_projections_team_channel",
            columnList = "team_id,channel_id",
        ),
    ],
)
class ChannelRolePolicyProjection(
    @Id
    @Column(name = "policy_key", nullable = false, length = 120)
    val policyKey: String,

    @Column(name = "team_id", nullable = false)
    val teamId: Long,

    @Column(name = "channel_id", nullable = false)
    val channelId: Long,

    @Column(name = "role_id", nullable = false)
    val roleId: Long,

    @Column(name = "permissions_json", columnDefinition = "JSON")
    var permissionsJson: String? = null,

    @Column(nullable = false)
    var deleted: Boolean = false,

    @Column(name = "source_occurred_at", nullable = false)
    var sourceOccurredAt: Instant,
) {
    fun applyUpsert(permissionsJson: String, occurredAt: Instant) {
        this.permissionsJson = permissionsJson
        deleted = false
        sourceOccurredAt = occurredAt
    }

    fun markDeleted(occurredAt: Instant) {
        permissionsJson = null
        deleted = true
        sourceOccurredAt = occurredAt
    }

    companion object {
        fun key(teamId: Long, channelId: Long, roleId: Long): String = "policy:$teamId:$channelId:$roleId"
    }
}
