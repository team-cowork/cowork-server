package com.cowork.channel.domain.membership.repository

import com.cowork.channel.domain.membership.entity.TeamMembership
import org.springframework.data.jpa.repository.JpaRepository

interface TeamMembershipRepository : JpaRepository<TeamMembership, Long> {
    fun findByTeamIdAndUserId(teamId: Long, userId: Long): TeamMembership?
    fun findAllByTeamIdAndUserIdIn(teamId: Long, userIds: List<Long>): List<TeamMembership>
    fun deleteAllByTeamId(teamId: Long)
    fun deleteByTeamIdAndUserId(teamId: Long, userId: Long)
}
