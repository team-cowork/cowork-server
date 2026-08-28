package com.cowork.team.domain.teamMember.repository

import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamRole.entity.TeamRole
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface TeamMemberRepository : JpaRepository<TeamMember, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT tm FROM TeamMember tm WHERE tm.team.id = :teamId ORDER BY tm.id")
    fun findAllByTeamIdForUpdate(@Param("teamId") teamId: Long): List<TeamMember>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT tm FROM TeamMember tm WHERE tm.team.id = :teamId AND tm.userId = :userId")
    fun findByTeamIdAndUserIdForUpdate(@Param("teamId") teamId: Long, @Param("userId") userId: Long): TeamMember?

    fun findAllByTeamId(teamId: Long): List<TeamMember>

    @Query("SELECT tm.id FROM TeamMember tm")
    fun findAllIds(pageable: Pageable): Slice<Long>

    @Query("SELECT tm FROM TeamMember tm JOIN FETCH tm.team WHERE tm.id IN :ids")
    fun findAllWithTeamByIds(ids: List<Long>): List<TeamMember>

    @Query("SELECT tm FROM TeamMember tm JOIN FETCH tm.team WHERE tm.userId = :userId")
    fun findAllByUserIdWithTeam(userId: Long): List<TeamMember>

    fun findByTeamIdAndUserId(teamId: Long, userId: Long): TeamMember?

    fun existsByTeamIdAndUserId(teamId: Long, userId: Long): Boolean

    fun findByTeamIdAndUserIdAndRoleIn(teamId: Long, userId: Long, roles: List<TeamRole>): TeamMember?
}
