package com.cowork.channel.domain.membership.repository

import com.cowork.channel.domain.membership.entity.TeamMembership
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface TeamMembershipRepository : JpaRepository<TeamMembership, Long> {
    @Query(
        "SELECT membership FROM TeamMembership membership " +
            "WHERE membership.teamId = :teamId AND membership.userId = :userId AND membership.active = true",
    )
    fun findByTeamIdAndUserId(@Param("teamId") teamId: Long, @Param("userId") userId: Long): TeamMembership?

    @Query(
        "SELECT membership FROM TeamMembership membership " +
            "WHERE membership.teamId = :teamId AND membership.userId = :userId",
    )
    fun findStateByTeamIdAndUserId(@Param("teamId") teamId: Long, @Param("userId") userId: Long): TeamMembership?

    fun findAllByTeamId(teamId: Long): List<TeamMembership>
}
