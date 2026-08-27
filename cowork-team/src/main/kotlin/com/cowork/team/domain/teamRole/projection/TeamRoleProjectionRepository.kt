package com.cowork.team.domain.teamRole.projection

import org.springframework.data.jpa.repository.JpaRepository

interface TeamRoleProjectionRepository : JpaRepository<TeamRoleProjection, Long> {
    fun findAllByTeamIdAndDeletedFalseOrderByPriorityDescRoleIdAsc(teamId: Long): List<TeamRoleProjection>
}

interface TeamRoleAssignmentProjectionRepository : JpaRepository<TeamRoleAssignmentProjection, String> {
    fun findAllByTeamIdAndDeletedFalse(teamId: Long): List<TeamRoleAssignmentProjection>
    fun findAllByTeamIdAndAccountId(teamId: Long, accountId: Long): List<TeamRoleAssignmentProjection>
    fun findAllByTeamIdAndRoleId(teamId: Long, roleId: Long): List<TeamRoleAssignmentProjection>
}

interface TeamRoleMemberTombstoneRepository : JpaRepository<TeamRoleMemberTombstone, String>
