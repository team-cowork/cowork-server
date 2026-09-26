package com.cowork.team.domain.team.repository

import com.cowork.team.domain.team.entity.TeamGithubInstallationRevision
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface TeamGithubInstallationRevisionRepository : JpaRepository<TeamGithubInstallationRevision, Long> {
    @Modifying
    @Query(
        value = """
            INSERT INTO tb_team_github_installation_revisions (installation_id, revision, updated_at)
            VALUES (:installationId, 0, CURRENT_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE installation_id = installation_id
        """,
        nativeQuery = true,
    )
    fun insertInitialIfAbsent(@Param("installationId") installationId: Long): Int

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT r FROM TeamGithubInstallationRevision r WHERE r.installationId = :installationId",
    )
    fun findByInstallationIdForUpdate(@Param("installationId") installationId: Long): TeamGithubInstallationRevision?
}
