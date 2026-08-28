package com.cowork.project.domain.project.repository

import com.cowork.project.domain.project.event.ProjectEventTombstone
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ProjectEventTombstoneRepository : JpaRepository<ProjectEventTombstone, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT tombstone FROM ProjectEventTombstone tombstone WHERE tombstone.projectId = :projectId")
    fun findByProjectIdForUpdate(@Param("projectId") projectId: Long): ProjectEventTombstone?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT tombstone FROM ProjectEventTombstone tombstone " +
            "WHERE tombstone.projectId > :afterProjectId ORDER BY tombstone.projectId",
    )
    fun findSnapshotBatch(
        @Param("afterProjectId") afterProjectId: Long,
        pageable: Pageable,
    ): List<ProjectEventTombstone>
}
