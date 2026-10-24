package com.cowork.team.domain.teamRole.operation

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
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

    // TODO(#596): 2026-11-01 05:00 KST 이후 아래 조건 완료를 확인하고 제거한다.
    // 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
    // 구형 producer 전환, 저장 결과 보정과 구형 결과 재처리 종료를 확인한 뒤 제거한다.
    @Modifying
    @Query(
        value = """
            UPDATE tb_team_role_command_operations o
            SET result_role_json = JSON_SET(result_role_json, '$.isDefault', CAST(IF(EXISTS (
                SELECT 1 FROM tb_team_role_projections p
                WHERE p.role_id = CAST(JSON_UNQUOTE(JSON_EXTRACT(o.result_role_json, '$.id')) AS UNSIGNED)
                  AND p.team_id = o.team_id AND p.is_default = TRUE
            ), 'true', 'false') AS JSON))
            WHERE result_role_json IS NOT NULL
              AND JSON_TYPE(result_role_json) = 'OBJECT'
              AND (JSON_CONTAINS_PATH(result_role_json, 'one', '$.isDefault') = 0
                OR (JSON_EXTRACT(result_role_json, '$.isDefault') = CAST('false' AS JSON) AND EXISTS (
                    SELECT 1 FROM tb_team_role_projections p
                    WHERE p.role_id = CAST(JSON_UNQUOTE(JSON_EXTRACT(o.result_role_json, '$.id')) AS UNSIGNED)
                      AND p.team_id = o.team_id AND p.is_default = TRUE
                )))
            ORDER BY operation_id
            LIMIT 1000
        """,
        nativeQuery = true,
    )
    fun repairLegacyResultRoles(): Int

    @Lock(LockModeType.PESSIMISTIC_WRITE)
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
