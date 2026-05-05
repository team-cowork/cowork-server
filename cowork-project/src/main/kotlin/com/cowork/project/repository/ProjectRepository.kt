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

    @Query("SELECT p.id FROM Project p WHERE p.teamId = :teamId")
    fun findIdsByTeamId(@Param("teamId") teamId: Long): List<Long>

    fun findByTeamId(teamId: Long, pageable: Pageable): Page<Project>

    @Query(
        value = "SELECT DISTINCT p FROM Project p JOIN ProjectMember m ON m.projectId = p.id WHERE m.userId = :userId",
        countQuery = "SELECT COUNT(DISTINCT p) FROM Project p JOIN ProjectMember m ON m.projectId = p.id WHERE m.userId = :userId",
    )
    fun findProjectsByMemberUserId(@Param("userId") userId: Long, pageable: Pageable): Page<Project>
}
