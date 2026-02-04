package com.cowork.team.repository

import com.cowork.team.domain.TeamRole
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface TeamRoleRepository : JpaRepository<TeamRole, Long> {

    fun findAllByTeamIdOrderByPriorityDescIdAsc(teamId: Long): List<TeamRole>

    fun findByIdAndTeamId(id: Long, teamId: Long): TeamRole?

    fun existsByTeamIdAndName(teamId: Long, name: String): Boolean

    @Query(
        """
        SELECT r FROM TeamRole r
        WHERE r.id IN (SELECT a.roleId FROM TeamRoleAssignment a WHERE a.teamId = :teamId AND a.userId = :userId)
        ORDER BY r.priority DESC, r.id ASC
        """,
    )
    fun findAllAssignedTo(teamId: Long, userId: Long): List<TeamRole>

    @Modifying
    @Query("DELETE FROM TeamRole r WHERE r.teamId = :teamId")
    fun deleteAllByTeamId(teamId: Long)
}
