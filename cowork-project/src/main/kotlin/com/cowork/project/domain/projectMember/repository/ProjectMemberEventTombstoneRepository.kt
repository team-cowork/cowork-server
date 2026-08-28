package com.cowork.project.domain.projectMember.repository

import com.cowork.project.domain.projectMember.event.ProjectMemberEventTombstone
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ProjectMemberEventTombstoneRepository : JpaRepository<ProjectMemberEventTombstone, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT tombstone FROM ProjectMemberEventTombstone tombstone " +
            "WHERE tombstone.projectId = :projectId AND tombstone.userId = :userId",
    )
    fun findByProjectIdAndUserIdForUpdate(
        @Param("projectId") projectId: Long,
        @Param("userId") userId: Long,
    ): ProjectMemberEventTombstone?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT tombstone FROM ProjectMemberEventTombstone tombstone " +
            "WHERE tombstone.id > :afterId ORDER BY tombstone.id",
    )
    fun findSnapshotBatch(@Param("afterId") afterId: Long, pageable: Pageable): List<ProjectMemberEventTombstone>
}
