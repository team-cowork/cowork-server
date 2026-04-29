package com.cowork.project.repository

import com.cowork.project.domain.ProjectMember
import com.cowork.project.domain.ProjectMemberRole
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor

interface ProjectMemberRepository : JpaRepository<ProjectMember, Long>, JpaSpecificationExecutor<ProjectMember> {

    fun findByProjectId(projectId: Long): List<ProjectMember>

    fun findByProjectIdAndUserId(projectId: Long, userId: Long): ProjectMember?

    fun countByProjectId(projectId: Long): Long

    fun existsByProjectIdAndUserIdAndRole(projectId: Long, userId: Long, role: ProjectMemberRole): Boolean

    fun findAllByUserIdAndRole(userId: Long, role: ProjectMemberRole): List<ProjectMember>

    fun findAllByUserIdAndRoleAndProjectIdIn(userId: Long, role: ProjectMemberRole, projectIds: List<Long>): List<ProjectMember>

    fun deleteAllByUserId(userId: Long)

    fun deleteAllByUserIdAndProjectIdIn(userId: Long, projectIds: List<Long>)
}
