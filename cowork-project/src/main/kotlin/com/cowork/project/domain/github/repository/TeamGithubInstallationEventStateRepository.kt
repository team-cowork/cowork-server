package com.cowork.project.domain.github.repository

import com.cowork.project.domain.github.entity.TeamGithubInstallationEventState
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface TeamGithubInstallationEventStateRepository : JpaRepository<TeamGithubInstallationEventState, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT state FROM TeamGithubInstallationEventState state WHERE state.teamId = :teamId")
    fun findByTeamIdForUpdate(@Param("teamId") teamId: Long): TeamGithubInstallationEventState?
}
