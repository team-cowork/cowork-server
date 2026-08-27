package com.cowork.team.domain.teamRole.operation

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface TeamRoleCommandOperationRepository : JpaRepository<TeamRoleCommandOperation, String> {
    @Modifying
    @Query(
        value = """
            INSERT INTO tb_team_role_command_operations (
                operation_id, idempotency_key, team_id, actor_id, command_type,
                request_hash, status, created_at, updated_at
            ) VALUES (
                :operationId, :idempotencyKey, :teamId, :actorId, :commandType,
                :requestHash, 'PENDING', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
            ) ON DUPLICATE KEY UPDATE operation_id = operation_id
        """,
        nativeQuery = true,
    )
    fun insertPendingIfAbsent(
        @Param("operationId") operationId: String,
        @Param("idempotencyKey") idempotencyKey: String,
        @Param("teamId") teamId: Long,
        @Param("actorId") actorId: Long,
        @Param("commandType") commandType: String,
        @Param("requestHash") requestHash: String,
    ): Int

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT operation FROM TeamRoleCommandOperation operation WHERE operation.operationId = :operationId")
    fun findByIdForUpdate(@Param("operationId") operationId: String): TeamRoleCommandOperation?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT operation FROM TeamRoleCommandOperation operation " +
            "WHERE operation.actorId = :actorId AND operation.idempotencyKey = :idempotencyKey",
    )
    fun findByActorAndIdempotencyKeyForUpdate(
        @Param("actorId") actorId: Long,
        @Param("idempotencyKey") idempotencyKey: String,
    ): TeamRoleCommandOperation?

    fun findTopByStatusOrderByOperationIdDesc(status: TeamRoleOperationStatus): TeamRoleCommandOperation?

    @Query(
        "SELECT operation FROM TeamRoleCommandOperation operation " +
            "WHERE operation.status = :status " +
            "AND operation.operationId > :afterOperationId " +
            "AND operation.operationId <= :throughOperationId " +
            "ORDER BY operation.operationId",
    )
    fun findStatusBatch(
        @Param("status") status: TeamRoleOperationStatus,
        @Param("afterOperationId") afterOperationId: String,
        @Param("throughOperationId") throughOperationId: String,
        pageable: Pageable,
    ): List<TeamRoleCommandOperation>
}
