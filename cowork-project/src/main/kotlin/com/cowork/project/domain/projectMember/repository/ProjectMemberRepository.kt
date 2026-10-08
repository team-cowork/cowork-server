package com.cowork.project.domain.projectMember.repository

import com.cowork.project.domain.projectMember.entity.ProjectMember
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
    @Query("SELECT pm FROM ProjectMember pm WHERE pm.id = :memberId")
    fun findByIdForUpdate(@Param("memberId") memberId: Long): ProjectMember?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT pm FROM ProjectMember pm " +
            "WHERE pm.projectId = :projectId AND pm.userId = :userId",
    )
    fun findByProjectIdAndUserIdForUpdate(
        @Param("projectId") projectId: Long,
        @Param("userId") userId: Long,
    ): ProjectMember?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT pm FROM ProjectMember pm WHERE pm.projectId = :projectId ORDER BY pm.id")
    fun findAllByProjectIdForUpdate(@Param("projectId") projectId: Long): List<ProjectMember>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT pm FROM ProjectMember pm WHERE pm.projectId IN :projectIds ORDER BY pm.projectId, pm.id")
    fun findAllByProjectIdInForUpdate(@Param("projectIds") projectIds: Collection<Long>): List<ProjectMember>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT pm FROM ProjectMember pm " +
            "WHERE pm.userId = :userId AND pm.projectId IN :projectIds ORDER BY pm.projectId, pm.id",
    )
    fun findAllByUserIdAndProjectIdInForUpdate(
        @Param("userId") userId: Long,
        @Param("projectIds") projectIds: Collection<Long>,
    ): List<ProjectMember>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT pm FROM ProjectMember pm WHERE pm.projectId = :projectId ORDER BY pm.id")
    fun findSnapshotByProjectId(@Param("projectId") projectId: Long): List<ProjectMember>

    fun findByProjectId(projectId: Long): List<ProjectMember>

    fun findByProjectIdAndUserId(projectId: Long, userId: Long): ProjectMember?

    fun countByProjectId(projectId: Long): Long
}
