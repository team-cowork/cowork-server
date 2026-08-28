package com.cowork.preference.repository

import com.cowork.preference.messaging.GithubRepoSettingCommand
import com.cowork.preference.messaging.GithubRepoSettingCommandQuarantineRecord
import com.cowork.preference.messaging.GithubRepoSettingCommandType
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import java.time.Instant

data class ProcessedGithubRepoSettingCommand(
    val operationId: String,
    val idempotencyKey: String,
    val commandType: GithubRepoSettingCommandType,
    val repoId: Long,
    val requestedBy: Long?,
    val labelAutoApply: Boolean?,
    val commandOccurredAt: Instant,
    val result: JsonObject?,
)

data class ProcessedGithubRepoSettingCommandMatches(
    val byOperationId: ProcessedGithubRepoSettingCommand?,
    val byRequesterAndIdempotencyKey: ProcessedGithubRepoSettingCommand?,
)

class GithubRepoSettingCommandInboxRepository(private val pool: Pool) {
    suspend fun findMatches(
        client: SqlClient,
        command: GithubRepoSettingCommand,
    ): ProcessedGithubRepoSettingCommandMatches {
        val rows = client.preparedQuery(
            """
            SELECT operation_id, idempotency_key, command_type, repo_id, requested_by, label_auto_apply,
                   command_occurred_at, result::text AS result
            FROM tb_github_repo_setting_command_inbox
            WHERE operation_id = ${'$'}1
               OR (requested_by IS NOT DISTINCT FROM ${'$'}2 AND idempotency_key = ${'$'}3)
            FOR UPDATE
            """.trimIndent(),
        ).execute(Tuple.of(command.operationId, command.requestedBy, command.idempotencyKey)).coAwait()
        val processed = rows.map { row ->
            ProcessedGithubRepoSettingCommand(
                operationId = row.getString("operation_id"),
                idempotencyKey = row.getString("idempotency_key"),
                commandType = GithubRepoSettingCommandType.valueOf(row.getString("command_type")),
                repoId = row.getLong("repo_id"),
                requestedBy = row.getLong("requested_by"),
                labelAutoApply = row.getBoolean("label_auto_apply"),
                commandOccurredAt = Instant.parse(row.getString("command_occurred_at")),
                result = row.getString("result")?.let(::JsonObject),
            )
        }
        return ProcessedGithubRepoSettingCommandMatches(
            byOperationId = processed.firstOrNull { it.operationId == command.operationId },
            byRequesterAndIdempotencyKey = processed.firstOrNull {
                it.requestedBy == command.requestedBy && it.idempotencyKey == command.idempotencyKey
            },
        )
    }

    suspend fun insert(client: SqlClient, command: GithubRepoSettingCommand, result: JsonObject?) {
        client.preparedQuery(
            """
            INSERT INTO tb_github_repo_setting_command_inbox
                (operation_id, idempotency_key, command_type, repo_id, requested_by, label_auto_apply,
                 command_occurred_at, result)
            VALUES (${'$'}1, ${'$'}2, ${'$'}3, ${'$'}4, ${'$'}5, ${'$'}6, ${'$'}7, ${'$'}8::jsonb)
            """.trimIndent(),
        ).execute(
            Tuple.of(
                command.operationId,
                command.idempotencyKey,
                command.commandType.name,
                command.repoId,
                command.requestedBy,
                command.labelAutoApply,
                command.occurredAt.toString(),
                result,
            ),
        ).coAwait()
    }

    suspend fun quarantine(record: GithubRepoSettingCommandQuarantineRecord): Boolean = quarantine(pool, record)

    suspend fun quarantine(client: SqlClient, record: GithubRepoSettingCommandQuarantineRecord): Boolean {
        val result = client.preparedQuery(
            """
            INSERT INTO tb_github_repo_setting_command_quarantine
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
