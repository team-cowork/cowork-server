package com.cowork.channel.domain.channelRolePolicy.projection

import org.springframework.data.jpa.repository.JpaRepository

interface TeamRoleProjectionRepository : JpaRepository<TeamRoleProjection, Long> {
    fun findAllByTeamIdAndRoleIdInAndDeletedFalse(teamId: Long, roleIds: Set<Long>): List<TeamRoleProjection>
}

interface TeamRoleAssignmentProjectionRepository : JpaRepository<TeamRoleAssignmentProjection, String> {
    fun findAllByTeamIdAndAccountIdAndDeletedFalse(teamId: Long, accountId: Long): List<TeamRoleAssignmentProjection>

    fun findAllByTeamIdAndAccountId(teamId: Long, accountId: Long): List<TeamRoleAssignmentProjection>

    fun findAllByTeamIdAndRoleId(teamId: Long, roleId: Long): List<TeamRoleAssignmentProjection>
}

interface TeamRoleMemberTombstoneRepository : JpaRepository<TeamRoleMemberTombstone, String>

interface ChannelRolePolicyProjectionRepository : JpaRepository<ChannelRolePolicyProjection, String> {
    fun findAllByTeamIdAndChannelIdAndDeletedFalse(teamId: Long, channelId: Long): List<ChannelRolePolicyProjection>

    fun findAllByTeamIdAndChannelIdInAndRoleIdInAndDeletedFalse(
        teamId: Long,
        channelIds: Set<Long>,
        roleIds: Set<Long>,
    ): List<ChannelRolePolicyProjection>
}
