package com.cowork.team.domain.teamRole.operation

import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Repository
class TeamRoleOperationSubmissionRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val operationRepository: TeamRoleCommandOperationRepository,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun insertPendingOrGet(
        operationId: String,
        idempotencyKey: String,
        teamId: Long,
        actorId: Long,
        commandType: TeamRoleCommandType,
        requestHash: String,
    ): InsertResult {
        try {
            // Handle the JDBC duplicate here so it cannot mark the JPA transaction rollback-only.
            jdbcTemplate.update(
                INSERT_SQL,
                operationId,
                idempotencyKey,
                teamId,
                actorId,
                commandType.name,
                requestHash,
            )
        } catch (exception: DuplicateKeyException) {
            // Duplicate INSERT holds a shared lock; replay must not upgrade it to a write lock.
            val existing = operationRepository.findByActorAndIdempotencyKeyForShare(actorId, idempotencyKey)
                ?: throw exception
            return InsertResult.Existing(existing)
        }
        return InsertResult.Inserted
    }

    sealed interface InsertResult {
        data object Inserted : InsertResult
        data class Existing(val operation: TeamRoleCommandOperation) : InsertResult
    }

    private companion object {
        val INSERT_SQL = """
            INSERT INTO tb_team_role_command_operations (
                operation_id, idempotency_key, team_id, actor_id, command_type,
                request_hash, status, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
        """.trimIndent()
    }
}
