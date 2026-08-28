package com.cowork.team.domain.team.repository

import com.cowork.team.domain.team.entity.Team
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface TeamRepository : JpaRepository<Team, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Team t WHERE t.id = :teamId")
    fun findByIdForUpdate(@Param("teamId") teamId: Long): Team?

    fun findByGithubInstallationId(installationId: Long): Team?
}
