package com.cowork.project.domain.github.repository

import com.cowork.project.domain.github.event.ProjectGithubRepoEventTombstone
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ProjectGithubRepoEventTombstoneRepository : JpaRepository<ProjectGithubRepoEventTombstone, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT tombstone FROM ProjectGithubRepoEventTombstone tombstone " +
            "WHERE tombstone.repoId = :repoId",
    )
    fun findByRepoIdForUpdate(@Param("repoId") repoId: Long): ProjectGithubRepoEventTombstone?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT tombstone FROM ProjectGithubRepoEventTombstone tombstone " +
            "WHERE tombstone.repoId > :afterRepoId ORDER BY tombstone.repoId",
    )
    fun findSnapshotBatch(
        @Param("afterRepoId") afterRepoId: Long,
        pageable: Pageable,
    ): List<ProjectGithubRepoEventTombstone>
}
