package com.cowork.project.repository

import com.cowork.project.domain.Project
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ProjectRepository : JpaRepository<Project, Long>, JpaSpecificationExecutor<Project> {

    fun findAllByTeamId(teamId: Long): List<Project>

    fun findByTeamId(teamId: Long, pageable: Pageable): Page<Project>

    @Query(
        value = """
            SELECT p FROM Project p
            WHERE p.id IN (
                SELECT m.projectId FROM ProjectMember m WHERE m.userId = :userId
            )
        """,
        countQuery = """
            SELECT COUNT(p) FROM Project p
            WHERE p.id IN (
                SELECT m.projectId FROM ProjectMember m WHERE m.userId = :userId
            )
        """,
    )
    fun findProjectsByMemberUserId(@Param("userId") userId: Long, pageable: Pageable): Page<Project>
}
