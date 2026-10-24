package com.cowork.project.domain.project.repository

import com.cowork.project.domain.project.entity.Project
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ProjectRepository :
    JpaRepository<Project, Long>,
    JpaSpecificationExecutor<Project> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Project p WHERE p.id = :projectId")
    fun findByIdForUpdate(@Param("projectId") projectId: Long): Project?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Project p WHERE p.teamId = :teamId ORDER BY p.id")
    fun findAllByTeamIdForUpdate(@Param("teamId") teamId: Long): List<Project>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Project p WHERE p.id > :afterId ORDER BY p.id")
    fun findSnapshotBatch(@Param("afterId") afterId: Long, pageable: Pageable): List<Project>

    fun findAllByTeamId(teamId: Long): List<Project>

    @Query("SELECT p.id FROM Project p WHERE p.teamId = :teamId")
    fun findIdsByTeamId(@Param("teamId") teamId: Long): List<Long>

    @Query(
        "SELECT p FROM Project p JOIN TeamMembership m ON m.teamId = p.teamId " +
            "WHERE p.teamId = :teamId AND m.userId = :userId AND m.active = true " +
            "AND (m.role IN ('OWNER', 'ADMIN') OR EXISTS (" +
            "SELECT a.accessKey FROM ProjectAccessProjection a WHERE a.projectId = p.id AND a.userId = :userId AND a.canRead = true AND a.sourceMembershipVersion = m.sourceOccurredAt))",
    )
    fun findReadableByTeamId(@Param("teamId") teamId: Long, @Param("userId") userId: Long, pageable: Pageable): Page<Project>

    @Query(
        "SELECT COUNT(m) FROM Project p JOIN TeamMembership m ON m.teamId = p.teamId " +
            "WHERE p.id = :projectId AND m.active = true " +
            "AND (m.role IN ('OWNER', 'ADMIN') OR EXISTS (" +
            "SELECT a.accessKey FROM ProjectAccessProjection a WHERE a.projectId = p.id AND a.userId = m.userId AND a.canRead = true AND a.sourceMembershipVersion = m.sourceOccurredAt))",
    )
    fun countReadableUsers(@Param("projectId") projectId: Long): Long

    @Query("SELECT COALESCE(MAX(p.position), -1) FROM Project p WHERE p.teamId = :teamId")
    fun findMaxPositionByTeamId(@Param("teamId") teamId: Long): Int

    @Query(
        "SELECT p FROM Project p JOIN TeamMembership m ON m.teamId = p.teamId " +
            "WHERE m.userId = :userId AND m.active = true " +
            "AND (m.role IN ('OWNER', 'ADMIN') OR EXISTS (" +
            "SELECT a.accessKey FROM ProjectAccessProjection a WHERE a.projectId = p.id AND a.userId = :userId AND a.canRead = true AND a.sourceMembershipVersion = m.sourceOccurredAt))",
    )
    fun findReadableByUserId(@Param("userId") userId: Long, pageable: Pageable): Page<Project>
}
