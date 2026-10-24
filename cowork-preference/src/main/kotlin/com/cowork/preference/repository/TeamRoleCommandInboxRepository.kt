package com.cowork.preference.repository

import com.cowork.preference.messaging.TeamRoleCommand
import com.cowork.preference.messaging.TeamRoleCommandQuarantineRecord
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

data class ProcessedTeamRoleCommand(
    val operationId: String,
    val actorId: Long,
    val idempotencyKey: String,
    val requestHash: String,
    val commandType: String,
    val teamId: Long,
    val result: JsonObject,
)

data class ProcessedTeamRoleCommandMatches(
    val byOperationId: ProcessedTeamRoleCommand?,
    val byActorAndIdempotencyKey: ProcessedTeamRoleCommand?,
)

class TeamRoleCommandInboxRepository(private val pool: Pool) {
    // TODO(#596): 2026-11-01 05:00 KST 이후 아래 조건 완료를 확인하고 제거한다.
    // 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
    // 저장 결과 보정 완료, 구형 producer 전환과 결과 재처리 종료 확인 후 제거한다.
    internal suspend fun repairLegacyResults(client: SqlClient): Int = client.query(
        """
        WITH legacy AS (
            SELECT i.operation_id, COALESCE(d.is_default, false) AS is_default
            FROM tb_team_role_command_inbox i
            LEFT JOIN tb_team_role_definitions d ON d.id = (i.result #>> '{role,id}')::bigint AND d.team_id = i.team_id
            WHERE jsonb_typeof(i.result -> 'role') = 'object'
              AND (NOT (i.result -> 'role' ? 'isDefault') OR (d.is_default AND i.result #> '{role,isDefault}' = 'false'::jsonb))
            ORDER BY i.operation_id LIMIT 1000
            FOR UPDATE OF i
        )
        UPDATE tb_team_role_command_inbox i
        SET result = jsonb_set(i.result, '{role,isDefault}', to_jsonb(legacy.is_default))
        FROM legacy WHERE i.operation_id = legacy.operation_id
        """.trimIndent(),
    ).execute().coAwait().rowCount()

    suspend fun findMatches(client: SqlClient, command: TeamRoleCommand): ProcessedTeamRoleCommandMatches {
        val rows = client.preparedQuery(
            """
            SELECT operation_id, actor_id, idempotency_key, request_hash, command_type, team_id,
                   result::text AS result
            FROM tb_team_role_command_inbox
            WHERE operation_id = ${'$'}1
               OR (actor_id = ${'$'}2 AND idempotency_key = ${'$'}3)
            FOR UPDATE
            """.trimIndent(),
        ).execute(Tuple.of(command.operationId, command.actorId, command.idempotencyKey)).coAwait()
        val processed = rows.map { row ->
            ProcessedTeamRoleCommand(
                operationId = row.getString("operation_id"),
                actorId = row.getLong("actor_id"),
                idempotencyKey = row.getString("idempotency_key"),
                requestHash = row.getString("request_hash"),
                commandType = row.getString("command_type"),
                teamId = row.getLong("team_id"),
                result = JsonObject(row.getString("result")),
            )
        }
        return ProcessedTeamRoleCommandMatches(
            byOperationId = processed.firstOrNull { it.operationId == command.operationId },
            byActorAndIdempotencyKey = processed.firstOrNull {
                it.actorId == command.actorId && it.idempotencyKey == command.idempotencyKey
            },
        )
    }

    suspend fun insert(client: SqlClient, command: TeamRoleCommand, result: JsonObject) {
        client.preparedQuery(
            """
            INSERT INTO tb_team_role_command_inbox
                (operation_id, actor_id, idempotency_key, request_hash, command_type, team_id, result)
            VALUES (${'$'}1, ${'$'}2, ${'$'}3, ${'$'}4, ${'$'}5, ${'$'}6, ${'$'}7::jsonb)
            """.trimIndent(),
        ).execute(
            Tuple.of(
                command.operationId,
                command.actorId,
                command.idempotencyKey,
                command.requestHash,
                command.commandType.name,
                command.teamId,
                result,
            ),
        ).coAwait()
    }

    suspend fun quarantine(record: TeamRoleCommandQuarantineRecord): Boolean = quarantine(pool, record)

    suspend fun quarantine(client: SqlClient, record: TeamRoleCommandQuarantineRecord): Boolean {
        val result = client.preparedQuery(
            """
            INSERT INTO tb_team_role_command_quarantine
                (topic, partition_id, record_offset, record_key, payload, reason)
            VALUES (${'$'}1, ${'$'}2, ${'$'}3, ${'$'}4, ${'$'}5, ${'$'}6)
            ON CONFLICT (topic, partition_id, record_offset) DO NOTHING
            """.trimIndent(),
        ).execute(
            Tuple.of(record.topic, record.partition, record.offset, record.key, record.payload, record.reason),
        ).coAwait()
        return result.rowCount() == 1
    }
}
