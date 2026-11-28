package com.cowork.team.domain.teamMember.repository

import com.cowork.team.domain.teamMember.entity.TeamMember
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface TeamMemberRepository : JpaRepository<TeamMember, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT tm FROM TeamMember tm WHERE tm.teamId = :teamId ORDER BY tm.id")
    fun findAllByTeamIdForUpdate(@Param("teamId") teamId: Long): List<TeamMember>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT tm FROM TeamMember tm WHERE tm.teamId = :teamId AND tm.userId = :userId")
    fun findByTeamIdAndUserIdForUpdate(@Param("teamId") teamId: Long, @Param("userId") userId: Long): TeamMember?

    fun findAllByTeamId(teamId: Long): List<TeamMember>

    fun findAllByUserId(userId: Long): List<TeamMember>

    fun existsByTeamIdAndUserId(teamId: Long, userId: Long): Boolean
}
