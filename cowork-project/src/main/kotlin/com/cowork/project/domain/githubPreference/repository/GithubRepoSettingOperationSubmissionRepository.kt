package com.cowork.project.domain.githubPreference.repository

import com.cowork.project.domain.githubPreference.entity.GithubRepoSettingOperation
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Repository
class GithubRepoSettingOperationSubmissionRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val operationRepository: GithubRepoSettingOperationRepository,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun insertPendingOrGet(
        operationId: String,
        idempotencyKey: String,
        projectId: Long,
        repoId: Long,
        requestedBy: Long,
        requestedAutoApply: Boolean,
    ): InsertResult {
        try {
            // Handle the JDBC duplicate here so it cannot mark the JPA transaction rollback-only.
            jdbcTemplate.update(
                INSERT_SQL,
                operationId,
                idempotencyKey,
                projectId,
                repoId,
                requestedBy,
                requestedAutoApply,
            )
        } catch (exception: DuplicateKeyException) {
            // Duplicate INSERT holds a shared lock; replay must not upgrade it to a write lock.
            val existing = operationRepository.findByRequesterAndIdempotencyKeyForShare(requestedBy, idempotencyKey)
                ?: throw exception
            return InsertResult.Existing(existing)
        }
        return InsertResult.Inserted
    }

    sealed interface InsertResult {
        data object Inserted : InsertResult
        data class Existing(val operation: GithubRepoSettingOperation) : InsertResult
    }

    private companion object {
        val INSERT_SQL = """
            INSERT INTO tb_github_repo_setting_operations (
                operation_id, idempotency_key, project_id, repo_id, requested_by,
                requested_auto_apply, status, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
        """.trimIndent()
    }
}
