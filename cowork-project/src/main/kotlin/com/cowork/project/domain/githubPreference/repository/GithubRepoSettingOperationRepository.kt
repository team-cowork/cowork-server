package com.cowork.project.domain.githubPreference.repository

import com.cowork.project.domain.githubPreference.entity.GithubRepoSettingOperation
import com.cowork.project.domain.githubPreference.entity.GithubRepoSettingOperationStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface GithubRepoSettingOperationRepository : JpaRepository<GithubRepoSettingOperation, String> {
    @Modifying
    @Query(
        value = """
            INSERT INTO tb_github_repo_setting_operations (
                operation_id, idempotency_key, project_id, repo_id, requested_by,
                requested_auto_apply, status, created_at, updated_at
            ) VALUES (
                :operationId, :idempotencyKey, :projectId, :repoId, :requestedBy,
                :requestedAutoApply, 'PENDING', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
            ) ON DUPLICATE KEY UPDATE operation_id = operation_id
        """,
        nativeQuery = true,
    )
    fun insertPendingIfAbsent(
        @Param("operationId") operationId: String,
        @Param("idempotencyKey") idempotencyKey: String,
        @Param("projectId") projectId: Long,
        @Param("repoId") repoId: Long,
        @Param("requestedBy") requestedBy: Long,
        @Param("requestedAutoApply") requestedAutoApply: Boolean,
    ): Int

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT operation FROM GithubRepoSettingOperation operation " +
            "WHERE operation.requestedBy = :requestedBy AND operation.idempotencyKey = :idempotencyKey",
    )
    fun findByRequesterAndIdempotencyKeyForUpdate(
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
