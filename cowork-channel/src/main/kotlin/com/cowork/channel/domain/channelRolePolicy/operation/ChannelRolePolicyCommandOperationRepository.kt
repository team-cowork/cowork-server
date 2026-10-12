package com.cowork.channel.domain.channelRolePolicy.operation

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ChannelRolePolicyCommandOperationRepository : JpaRepository<ChannelRolePolicyCommandOperation, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT operation FROM ChannelRolePolicyCommandOperation operation " +
            "WHERE operation.operationId = :operationId",
    )
    fun findByIdForUpdate(@Param("operationId") operationId: String): ChannelRolePolicyCommandOperation?

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query(
        "SELECT operation FROM ChannelRolePolicyCommandOperation operation " +
            "WHERE operation.actorId = :actorId AND operation.idempotencyKey = :idempotencyKey",
    )
    fun findByActorAndIdempotencyKeyForShare(
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
