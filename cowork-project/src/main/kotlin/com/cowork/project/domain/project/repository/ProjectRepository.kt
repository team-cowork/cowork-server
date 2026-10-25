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

    // TODO(#596): 2026-11-01 05:00 KST 이후 구형 producer·재처리·롤백 종료와 snapshot 보정 완료 확인 후 아래 조회의 COALESCE fallback 제거.
    // 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
    @Query(
        "SELECT p FROM Project p JOIN TeamMembership m ON m.teamId = p.teamId " +
            "WHERE p.teamId = :teamId AND m.userId = :userId AND m.active = true " +
            "AND (m.role IN ('OWNER', 'ADMIN') OR EXISTS (" +
            "SELECT a.accessKey FROM ProjectAccessProjection a WHERE a.projectId = p.id AND a.userId = :userId AND a.canRead = true AND COALESCE(a.sourceAuthorizationVersion, a.sourceMembershipVersion) = COALESCE(m.authorizationVersion, m.sourceOccurredAt)))",
    )
    fun findReadableByTeamId(@Param("teamId") teamId: Long, @Param("userId") userId: Long, pageable: Pageable): Page<Project>

    @Query(
        "SELECT COUNT(m) FROM Project p JOIN TeamMembership m ON m.teamId = p.teamId " +
            "WHERE p.id = :projectId AND m.active = true " +
            "AND (m.role IN ('OWNER', 'ADMIN') OR EXISTS (" +
            "SELECT a.accessKey FROM ProjectAccessProjection a WHERE a.projectId = p.id AND a.userId = m.userId AND a.canRead = true AND COALESCE(a.sourceAuthorizationVersion, a.sourceMembershipVersion) = COALESCE(m.authorizationVersion, m.sourceOccurredAt)))",
    )
    fun countReadableUsers(@Param("projectId") projectId: Long): Long

    @Query(
        "SELECT p FROM Project p JOIN TeamMembership m ON m.teamId = p.teamId " +
            "WHERE m.userId = :userId AND m.active = true " +
            "AND (m.role IN ('OWNER', 'ADMIN') OR EXISTS (" +
            "SELECT a.accessKey FROM ProjectAccessProjection a WHERE a.projectId = p.id AND a.userId = :userId AND a.canRead = true AND COALESCE(a.sourceAuthorizationVersion, a.sourceMembershipVersion) = COALESCE(m.authorizationVersion, m.sourceOccurredAt)))",
    )
    fun findReadableByUserId(@Param("userId") userId: Long, pageable: Pageable): Page<Project>
}
