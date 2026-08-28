package com.cowork.project.domain.github.repository

import com.cowork.project.domain.github.entity.ProjectGithubRepo
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ProjectGithubRepoRepository : JpaRepository<ProjectGithubRepo, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT repo FROM ProjectGithubRepo repo WHERE repo.id > :afterId ORDER BY repo.id")
    fun findSnapshotBatch(@Param("afterId") afterId: Long, pageable: Pageable): List<ProjectGithubRepo>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT repo FROM ProjectGithubRepo repo WHERE repo.id = :repoId")
    fun findByIdForUpdate(@Param("repoId") repoId: Long): ProjectGithubRepo?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT repo FROM ProjectGithubRepo repo WHERE repo.id = :repoId AND repo.projectId = :projectId")
    fun findByIdAndProjectIdForUpdate(
        @Param("repoId") repoId: Long,
        @Param("projectId") projectId: Long,
    ): ProjectGithubRepo?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT repo FROM ProjectGithubRepo repo WHERE repo.projectId IN :projectIds ORDER BY repo.id")
    fun findAllByProjectIdInForUpdate(@Param("projectIds") projectIds: Collection<Long>): List<ProjectGithubRepo>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT repo FROM ProjectGithubRepo repo WHERE repo.githubWebhookChannelId = :channelId ORDER BY repo.id")
    fun findAllByGithubWebhookChannelIdForUpdate(@Param("channelId") channelId: Long): List<ProjectGithubRepo>

    fun findAllByProjectId(projectId: Long): List<ProjectGithubRepo>
    fun findAllByProjectIdIn(projectIds: Collection<Long>): List<ProjectGithubRepo>
    fun findByIdAndProjectId(id: Long, projectId: Long): ProjectGithubRepo?
    fun existsByTeamIdAndGithubRepoUrl(teamId: Long, githubRepoUrl: String): Boolean
    fun findAllByGithubRepoUrl(githubRepoUrl: String): List<ProjectGithubRepo>
    fun findAllByGithubWebhookChannelId(channelId: Long): List<ProjectGithubRepo>
}
