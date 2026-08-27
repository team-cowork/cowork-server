package com.cowork.team.domain.team.repository

import com.cowork.team.domain.team.entity.TeamEventState
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface TeamEventStateRepository : JpaRepository<TeamEventState, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT state FROM TeamEventState state WHERE state.teamId = :teamId")
    fun findByTeamIdForUpdate(@Param("teamId") teamId: Long): TeamEventState?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT state FROM TeamEventState state WHERE state.teamId > :afterId ORDER BY state.teamId")
    fun findSnapshotBatch(@Param("afterId") afterId: Long, pageable: Pageable): List<TeamEventState>
}
