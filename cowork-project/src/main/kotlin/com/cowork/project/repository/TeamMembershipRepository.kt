package com.cowork.project.repository

import com.cowork.project.domain.TeamMembership
import org.springframework.data.jpa.repository.JpaRepository

interface TeamMembershipRepository : JpaRepository<TeamMembership, Long> {
    fun findByTeamIdAndUserId(teamId: Long, userId: Long): TeamMembership?
    fun deleteAllByTeamId(teamId: Long)
    fun deleteByTeamIdAndUserId(teamId: Long, userId: Long)
}
