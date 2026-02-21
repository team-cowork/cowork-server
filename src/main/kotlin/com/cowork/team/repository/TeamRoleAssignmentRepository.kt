package com.cowork.team.repository

import com.cowork.team.domain.TeamRoleAssignment
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface TeamRoleAssignmentRepository : JpaRepository<TeamRoleAssignment, Long> {

    fun findByRoleIdAndUserId(roleId: Long, userId: Long): TeamRoleAssignment?

    fun existsByRoleIdAndUserId(roleId: Long, userId: Long): Boolean

    @Modifying
    @Query("DELETE FROM TeamRoleAssignment a WHERE a.roleId = :roleId")
    fun deleteAllByRoleId(roleId: Long)

    @Modifying
    @Query("DELETE FROM TeamRoleAssignment a WHERE a.teamId = :teamId AND a.userId = :userId")
    fun deleteAllByTeamIdAndUserId(teamId: Long, userId: Long)

    @Modifying
    @Query("DELETE FROM TeamRoleAssignment a WHERE a.teamId = :teamId")
    fun deleteAllByTeamId(teamId: Long)
}
