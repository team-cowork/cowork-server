package com.cowork.team.repository

import com.cowork.team.domain.TeamInvite
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface TeamInviteRepository : JpaRepository<TeamInvite, Long> {

    fun findAllByTeamId(teamId: Long): List<TeamInvite>

    fun findByTeamIdAndInviteCode(teamId: Long, inviteCode: String): TeamInvite?

    @Query("SELECT ti FROM TeamInvite ti JOIN FETCH ti.team WHERE ti.inviteCode = :inviteCode AND ti.deletedAt IS NULL")
    fun findActiveByInviteCode(inviteCode: String): TeamInvite?

    fun existsByInviteCode(inviteCode: String): Boolean
}
