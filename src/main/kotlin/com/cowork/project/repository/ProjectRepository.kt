package com.cowork.project.repository

import com.cowork.project.domain.Project
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface ProjectRepository : JpaRepository<Project, Long> {

    fun findAllByTeamIdOrderByPositionAscIdAsc(teamId: Long): List<Project>

    @Query("SELECT COALESCE(MAX(p.position), -1) FROM Project p WHERE p.teamId = :teamId")
    fun findMaxPosition(teamId: Long): Int

    @Query(
        """
        SELECT p FROM Project p
        WHERE p.id IN (SELECT m.projectId FROM ProjectMember m WHERE m.userId = :userId)
        ORDER BY p.teamId ASC, p.position ASC, p.id ASC
        """,
    )
    fun findAllByMemberUserId(userId: Long): List<Project>

    @Query("SELECT p.id FROM Project p WHERE p.teamId = :teamId")
    fun findIdsByTeamId(teamId: Long): List<Long>
}
