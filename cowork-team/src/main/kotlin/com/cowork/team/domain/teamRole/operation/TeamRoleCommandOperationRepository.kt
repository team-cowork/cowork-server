package com.cowork.team.domain.teamRole.operation

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface TeamRoleCommandOperationRepository : JpaRepository<TeamRoleCommandOperation, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT operation FROM TeamRoleCommandOperation operation WHERE operation.operationId = :operationId")
    fun findByIdForUpdate(@Param("operationId") operationId: String): TeamRoleCommandOperation?

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query(
        "SELECT operation FROM TeamRoleCommandOperation operation " +
            "WHERE operation.actorId = :actorId AND operation.idempotencyKey = :idempotencyKey",
    )
    fun findByActorAndIdempotencyKeyForShare(
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
