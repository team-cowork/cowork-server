package com.cowork.project.repository

import com.cowork.project.domain.ProjectMember
import com.cowork.project.domain.ProjectMemberRole
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface ProjectMemberRepository : JpaRepository<ProjectMember, Long> {

    fun findByProjectIdAndUserId(projectId: Long, userId: Long): ProjectMember?

    fun existsByProjectIdAndUserId(projectId: Long, userId: Long): Boolean

    fun findAllByProjectIdOrderByJoinedAtAsc(projectId: Long): List<ProjectMember>

    fun countByProjectIdAndRole(projectId: Long, role: ProjectMemberRole): Long

    @Modifying
    @Query("DELETE FROM ProjectMember m WHERE m.projectId = :projectId")
    fun deleteAllByProjectId(projectId: Long)

    @Modifying
    @Query("DELETE FROM ProjectMember m WHERE m.projectId IN :projectIds")
    fun deleteAllByProjectIdIn(projectIds: Collection<Long>)

    @Modifying
    @Query("DELETE FROM ProjectMember m WHERE m.userId = :userId AND m.projectId IN :projectIds")
    fun deleteAllByUserIdAndProjectIdIn(userId: Long, projectIds: Collection<Long>)
}
