package com.cowork.project.domain.github.entity

import com.cowork.project.global.projection.toProjectionPrecision
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** installationId가 팀 partition 사이를 이동할 때 최신 소유권만 남기는 전역 fence. */
@Entity
@Table(name = "tb_github_installation_ownership_fences")
class TeamGithubInstallationOwnershipFence(
    @Id
    @Column(name = "installation_id", nullable = false)
    val installationId: Long,

    @Column(name = "owner_team_id", nullable = false)
    var ownerTeamId: Long,

    @Column(nullable = false)
    var active: Boolean,

    @Column(name = "source_occurred_at", nullable = false)
    var sourceOccurredAt: Instant,
) {
    fun activate(ownerTeamId: Long, occurredAt: Instant) {
        this.ownerTeamId = ownerTeamId
        active = true
        sourceOccurredAt = occurredAt.toProjectionPrecision()
    }

    fun deactivate(ownerTeamId: Long, occurredAt: Instant) {
        this.ownerTeamId = ownerTeamId
        active = false
        sourceOccurredAt = occurredAt.toProjectionPrecision()
    }
}
