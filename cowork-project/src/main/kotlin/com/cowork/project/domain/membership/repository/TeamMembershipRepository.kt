package com.cowork.project.domain.membership.repository

import com.cowork.project.domain.membership.entity.TeamMembership
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface TeamMembershipRepository : JpaRepository<TeamMembership, Long> {
    @Query(
        "SELECT membership FROM TeamMembership membership " +
            "WHERE membership.teamId = :teamId AND membership.userId = :userId AND membership.active = true",
    )
    fun findActiveByTeamIdAndUserId(@Param("teamId") teamId: Long, @Param("userId") userId: Long): TeamMembership?

    @Query(
        "SELECT membership FROM TeamMembership membership " +
            "WHERE membership.teamId = :teamId AND membership.userId = :userId",
    )
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findStateByTeamIdAndUserIdForUpdate(
        @Param("teamId") teamId: Long,
        @Param("userId") userId: Long,
    ): TeamMembership?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT membership FROM TeamMembership membership WHERE membership.teamId = :teamId ORDER BY membership.id")
    fun findAllByTeamIdForUpdate(@Param("teamId") teamId: Long): List<TeamMembership>
}
