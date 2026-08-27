package com.cowork.project.domain.github.entity

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

/**
 * `cowork-team`이 관리하는 팀 GitHub App 설치 정보의 로컬 read-model.
 * authoritative `team.lifecycle` full-state Kafka 이벤트로 동기화된다.
 */
@Entity
@Table(name = "tb_team_github_installations")
class TeamGithubInstallation(

    @Id
    @Column(name = "team_id", nullable = false)
    val teamId: Long,

    @Column(name = "installation_id", nullable = false)
    var installationId: Long,

    @Column(name = "org_login", nullable = false, length = 255)
    var orgLogin: String,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
) {
    fun update(installationId: Long, orgLogin: String) {
        this.installationId = installationId
        this.orgLogin = orgLogin
    }
}
