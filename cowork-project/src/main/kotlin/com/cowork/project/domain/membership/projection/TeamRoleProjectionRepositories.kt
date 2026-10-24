package com.cowork.project.domain.membership.projection

import org.springframework.data.jpa.repository.JpaRepository

interface TeamRoleProjectionRepository : JpaRepository<TeamRoleProjection, Long> {
    fun findAllByTeamIdAndIsDefaultTrueAndDeletedFalse(teamId: Long): List<TeamRoleProjection>

    fun findAllByTeamIdAndRoleIdInAndDeletedFalse(teamId: Long, roleIds: Set<Long>): List<TeamRoleProjection>
}

interface TeamRoleAssignmentProjectionRepository : JpaRepository<TeamRoleAssignmentProjection, String> {
    fun findAllByTeamIdAndAccountIdAndDeletedFalse(teamId: Long, accountId: Long): List<TeamRoleAssignmentProjection>

    fun findAllByTeamIdAndAccountIdInAndDeletedFalse(
        teamId: Long,
        accountIds: Set<Long>,
    ): List<TeamRoleAssignmentProjection>

    fun findAllByTeamIdAndAccountId(teamId: Long, accountId: Long): List<TeamRoleAssignmentProjection>

    fun findAllByTeamIdAndRoleId(teamId: Long, roleId: Long): List<TeamRoleAssignmentProjection>
}

interface TeamRoleMemberTombstoneRepository : JpaRepository<TeamRoleMemberTombstone, String>
