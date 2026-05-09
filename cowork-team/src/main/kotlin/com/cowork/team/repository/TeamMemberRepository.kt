package com.cowork.team.repository

import com.cowork.team.domain.TeamMember
import com.cowork.team.domain.TeamRole
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface TeamMemberRepository : JpaRepository<TeamMember, Long> {

    fun findAllByTeamId(teamId: Long): List<TeamMember>

    @Query("SELECT tm FROM TeamMember tm JOIN FETCH tm.team")
    fun findAllWithTeam(): List<TeamMember>

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
