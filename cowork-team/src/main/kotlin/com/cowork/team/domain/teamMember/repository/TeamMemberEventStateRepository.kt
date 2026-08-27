package com.cowork.team.domain.teamMember.repository

import com.cowork.team.domain.teamMember.entity.TeamMemberEventState
import com.cowork.team.domain.teamMember.entity.TeamMemberEventStateId
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface TeamMemberEventStateRepository : JpaRepository<TeamMemberEventState, TeamMemberEventStateId> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT state FROM TeamMemberEventState state " +
            "WHERE state.teamId = :teamId AND state.userId = :userId",
    )
    fun findByKeyForUpdate(@Param("teamId") teamId: Long, @Param("userId") userId: Long): TeamMemberEventState?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT state FROM TeamMemberEventState state " +
            "WHERE state.teamId > :afterTeamId " +
            "OR (state.teamId = :afterTeamId AND state.userId > :afterUserId) " +
            "ORDER BY state.teamId, state.userId",
    )
    fun findSnapshotBatch(
        @Param("afterTeamId") afterTeamId: Long,
        @Param("afterUserId") afterUserId: Long,
        pageable: Pageable,
    ): List<TeamMemberEventState>
}
