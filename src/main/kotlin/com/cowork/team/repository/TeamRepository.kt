package com.cowork.team.repository

import com.cowork.team.domain.Team
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface TeamRepository : JpaRepository<Team, Long> {

    @Query("SELECT t FROM Team t WHERE t.id IN (SELECT m.teamId FROM TeamMember m WHERE m.userId = :userId) ORDER BY t.id")
    fun findAllByMemberUserId(userId: Long): List<Team>
}
