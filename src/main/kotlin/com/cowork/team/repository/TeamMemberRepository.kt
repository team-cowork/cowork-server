package com.cowork.team.repository

import com.cowork.team.domain.TeamMember
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface TeamMemberRepository : JpaRepository<TeamMember, Long> {

    fun findByTeamIdAndUserId(teamId: Long, userId: Long): TeamMember?

    fun existsByTeamIdAndUserId(teamId: Long, userId: Long): Boolean

    fun findAllByTeamIdOrderByJoinedAtAsc(teamId: Long): List<TeamMember>

    fun countByTeamId(teamId: Long): Long

    fun findAllByUserId(userId: Long): List<TeamMember>

    @Query("SELECT m.teamId AS teamId, COUNT(m) AS count FROM TeamMember m WHERE m.teamId IN :teamIds GROUP BY m.teamId")
    fun countByTeamIds(teamIds: Collection<Long>): List<TeamMemberCount>

    @Query("SELECT m.userId FROM TeamMember m WHERE m.teamId = :teamId")
    fun findUserIdsByTeamId(teamId: Long): List<Long>

    @Modifying
    @Query("DELETE FROM TeamMember m WHERE m.teamId = :teamId")
    fun deleteAllByTeamId(teamId: Long)
}
