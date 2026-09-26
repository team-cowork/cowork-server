package com.cowork.team.domain.team.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "tb_team_github_installation_revisions")
class TeamGithubInstallationRevision(
    @Id
    @Column(name = "installation_id", nullable = false)
    val installationId: Long,

    @Column(nullable = false)
    var revision: Long = 0,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    fun advanceTo(revision: Long, occurredAt: Instant = Instant.now()) {
        this.revision = revision
        this.updatedAt = occurredAt
    }

    companion object {
        fun initial(installationId: Long): TeamGithubInstallationRevision =
            TeamGithubInstallationRevision(installationId = installationId, revision = 0)
    }
}
