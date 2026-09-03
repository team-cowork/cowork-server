package com.cowork.channel.domain.channelRolePolicy.operation

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ChannelRolePolicyCommandOperationRepository : JpaRepository<ChannelRolePolicyCommandOperation, String> {
    @Modifying
    @Query(
        value = """
            INSERT INTO tb_channel_role_policy_operations (
                operation_id, idempotency_key, team_id, channel_id, role_id, actor_id,
                command_type, request_hash, status, created_at, updated_at
            ) VALUES (
                :operationId, :idempotencyKey, :teamId, :channelId, :roleId, :actorId,
                :commandType, :requestHash, 'PENDING', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
            ) ON DUPLICATE KEY UPDATE operation_id = operation_id
        """,
        nativeQuery = true,
    )
    fun insertPendingIfAbsent(
        @Param("operationId") operationId: String,
        @Param("idempotencyKey") idempotencyKey: String,
        @Param("teamId") teamId: Long,
        @Param("channelId") channelId: Long,
        @Param("roleId") roleId: Long,
        @Param("actorId") actorId: Long,
        @Param("commandType") commandType: String,
        @Param("requestHash") requestHash: String,
    ): Int

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT operation FROM ChannelRolePolicyCommandOperation operation " +
            "WHERE operation.operationId = :operationId",
    )
    fun findByIdForUpdate(@Param("operationId") operationId: String): ChannelRolePolicyCommandOperation?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT operation FROM ChannelRolePolicyCommandOperation operation " +
            "WHERE operation.actorId = :actorId AND operation.idempotencyKey = :idempotencyKey",
    )
    fun findByActorAndIdempotencyKeyForUpdate(
        @Param("actorId") actorId: Long,
        @Param("idempotencyKey") idempotencyKey: String,
    ): ChannelRolePolicyCommandOperation?

    fun findTopByStatusOrderByOperationIdDesc(
        status: ChannelRolePolicyOperationStatus,
    ): ChannelRolePolicyCommandOperation?

    @Query(
        "SELECT operation FROM ChannelRolePolicyCommandOperation operation " +
            "WHERE operation.status = :status " +
            "AND operation.operationId > :afterOperationId " +
            "AND operation.operationId <= :throughOperationId " +
            "ORDER BY operation.operationId",
    )
    fun findStatusBatch(
        @Param("status") status: ChannelRolePolicyOperationStatus,
        @Param("afterOperationId") afterOperationId: String,
        @Param("throughOperationId") throughOperationId: String,
        pageable: Pageable,
    ): List<ChannelRolePolicyCommandOperation>
}
