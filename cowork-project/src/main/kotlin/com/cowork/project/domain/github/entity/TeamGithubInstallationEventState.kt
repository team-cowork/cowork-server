package com.cowork.project.domain.github.entity

import com.cowork.project.global.projection.toProjectionPrecision
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * team.lifecycle에서 관측한 팀별 GitHub installation 상태와 마지막 source version.
 *
 * active row를 삭제해도 이 ledger는 남아 stale snapshot이 연결을 되살리지 못하게 한다.
 */
@Entity
@Table(name = "tb_team_github_installation_event_states")
class TeamGithubInstallationEventState(
    @Id
    @Column(name = "team_id", nullable = false)
    val teamId: Long,

    @Column(name = "installation_id")
    var installationId: Long?,

    @Column(name = "org_login", length = 255)
    var orgLogin: String?,

    @Column(nullable = false)
    var deleted: Boolean,

    @Column(name = "source_occurred_at", nullable = false)
    var sourceOccurredAt: Instant,
) {
    fun applyActive(installationId: Long, orgLogin: String, occurredAt: Instant) {
        this.installationId = installationId
        this.orgLogin = orgLogin
        deleted = false
        sourceOccurredAt = occurredAt.toProjectionPrecision()
    }

    fun applyDeleted(installationId: Long?, orgLogin: String?, occurredAt: Instant) {
        if (installationId != null) this.installationId = installationId
        if (orgLogin != null) this.orgLogin = orgLogin
        deleted = true
        sourceOccurredAt = occurredAt.toProjectionPrecision()
    }
}
