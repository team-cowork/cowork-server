package com.cowork.project.domain.github.repository

import com.cowork.project.domain.github.entity.TeamGithubInstallationOwnershipFence
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface TeamGithubInstallationOwnershipFenceRepository :
    JpaRepository<TeamGithubInstallationOwnershipFence, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT fence FROM TeamGithubInstallationOwnershipFence fence " +
            "WHERE fence.installationId = :installationId",
    )
    fun findByInstallationIdForUpdate(
        @Param("installationId") installationId: Long,
    ): TeamGithubInstallationOwnershipFence?
}
