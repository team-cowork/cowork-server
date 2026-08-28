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
