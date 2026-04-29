package com.cowork.channel.repository

import com.cowork.channel.domain.TeamMembership
import org.springframework.data.jpa.repository.JpaRepository

interface TeamMembershipRepository : JpaRepository<TeamMembership, Long> {
    fun findByTeamIdAndUserId(teamId: Long, userId: Long): TeamMembership?
    fun deleteAllByTeamId(teamId: Long)
    fun deleteByTeamIdAndUserId(teamId: Long, userId: Long)
}
