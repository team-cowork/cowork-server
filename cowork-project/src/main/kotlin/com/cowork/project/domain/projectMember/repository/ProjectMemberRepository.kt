package com.cowork.project.domain.projectMember.repository

import com.cowork.project.domain.projectMember.entity.ProjectMember
import com.cowork.project.domain.projectMember.entity.ProjectMemberRole
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ProjectMemberRepository :
    JpaRepository<ProjectMember, Long>,
    JpaSpecificationExecutor<ProjectMember> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT pm FROM ProjectMember pm WHERE pm.projectId = :projectId ORDER BY pm.id")
    fun findSnapshotByProjectId(@Param("projectId") projectId: Long): List<ProjectMember>

    fun findByProjectId(projectId: Long): List<ProjectMember>

    fun findByProjectIdAndUserId(projectId: Long, userId: Long): ProjectMember?

    fun countByProjectId(projectId: Long): Long

    fun existsByProjectIdAndUserIdAndRole(projectId: Long, userId: Long, role: ProjectMemberRole): Boolean

    fun findAllByUserIdAndRole(userId: Long, role: ProjectMemberRole): List<ProjectMember>

    fun findAllByUserIdAndRoleAndProjectIdIn(
        userId: Long,
        role: ProjectMemberRole,
        projectIds: List<Long>,
    ): List<ProjectMember>

    fun findAllByProjectIdIn(projectIds: List<Long>): List<ProjectMember>

    fun findAllByUserId(userId: Long): List<ProjectMember>

    fun deleteAllByUserId(userId: Long)

    fun deleteAllByUserIdAndProjectIdIn(userId: Long, projectIds: List<Long>)
}
