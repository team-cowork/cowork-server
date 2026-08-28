package com.cowork.project.domain.github.repository

import com.cowork.project.domain.github.entity.TeamGithubInstallation
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface TeamGithubInstallationRepository : JpaRepository<TeamGithubInstallation, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT installation FROM TeamGithubInstallation installation WHERE installation.teamId = :teamId")
    fun findByTeamIdForUpdate(@Param("teamId") teamId: Long): TeamGithubInstallation?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT installation FROM TeamGithubInstallation installation " +
            "WHERE installation.installationId = :installationId",
    )
    fun findByInstallationIdForUpdate(@Param("installationId") installationId: Long): TeamGithubInstallation?
}
