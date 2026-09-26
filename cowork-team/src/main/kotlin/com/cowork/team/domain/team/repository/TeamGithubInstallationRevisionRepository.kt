package com.cowork.team.domain.team.repository

import com.cowork.team.domain.team.entity.TeamGithubInstallationRevision
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface TeamGithubInstallationRevisionRepository : JpaRepository<TeamGithubInstallationRevision, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT r FROM TeamGithubInstallationRevision r WHERE r.installationId = :installationId",
    )
    fun findByInstallationIdForUpdate(@Param("installationId") installationId: Long): TeamGithubInstallationRevision?
}
