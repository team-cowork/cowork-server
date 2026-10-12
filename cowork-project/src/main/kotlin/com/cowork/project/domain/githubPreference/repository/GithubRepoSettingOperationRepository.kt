package com.cowork.project.domain.githubPreference.repository

import com.cowork.project.domain.githubPreference.entity.GithubRepoSettingOperation
import com.cowork.project.domain.githubPreference.entity.GithubRepoSettingOperationStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface GithubRepoSettingOperationRepository : JpaRepository<GithubRepoSettingOperation, String> {
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query(
        "SELECT operation FROM GithubRepoSettingOperation operation " +
            "WHERE operation.requestedBy = :requestedBy AND operation.idempotencyKey = :idempotencyKey",
    )
    fun findByRequesterAndIdempotencyKeyForShare(
        @Param("requestedBy") requestedBy: Long,
        @Param("idempotencyKey") idempotencyKey: String,
    ): GithubRepoSettingOperation?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT operation FROM GithubRepoSettingOperation operation WHERE operation.operationId = :operationId")
    fun findByIdForUpdate(@Param("operationId") operationId: String): GithubRepoSettingOperation?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT operation FROM GithubRepoSettingOperation operation " +
            "WHERE operation.repoId = :repoId AND operation.status = :status ORDER BY operation.createdAt",
    )
    fun findAllByRepoIdAndStatusForUpdate(
        @Param("repoId") repoId: Long,
        @Param("status") status: GithubRepoSettingOperationStatus,
    ): List<GithubRepoSettingOperation>

    fun findByOperationIdAndProjectIdAndRepoId(
        operationId: String,
        projectId: Long,
        repoId: Long,
    ): GithubRepoSettingOperation?
}
