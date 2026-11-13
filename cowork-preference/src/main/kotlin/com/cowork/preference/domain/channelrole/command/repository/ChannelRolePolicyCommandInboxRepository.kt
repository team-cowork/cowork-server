package com.cowork.preference.domain.channelrole.command.repository

import com.cowork.preference.domain.channelrole.command.model.ChannelRolePolicyCommand
import com.cowork.preference.domain.channelrole.command.model.ChannelRolePolicyCommandType
import com.cowork.preference.domain.channelrole.command.model.ProcessedChannelRolePolicyCommand
import com.cowork.preference.global.messaging.command.model.CommandQuarantineRecord
import com.cowork.preference.global.messaging.command.model.ProcessedCommandMatches
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import java.time.Instant

class ChannelRolePolicyCommandInboxRepository(private val pool: Pool) {
    suspend fun findMatches(
        client: SqlClient,
        command: ChannelRolePolicyCommand,
    ): ProcessedCommandMatches<ProcessedChannelRolePolicyCommand> {
        val rows = client.preparedQuery(
            """
            SELECT operation_id, actor_id, idempotency_key, request_hash, command_type, team_id,
                   channel_id, role_id, actor_membership_version, permissions, result
            FROM tb_channel_role_policy_command_inbox
            WHERE operation_id = $1
               OR (actor_id = $2 AND idempotency_key = $3)
            FOR UPDATE
            """.trimIndent(),
        ).execute(Tuple.of(command.operationId, command.actorId, command.idempotencyKey)).coAwait()
        val processed = rows.map { row ->
            ProcessedChannelRolePolicyCommand(
                operationId = row.getString("operation_id"),
                actorId = row.getLong("actor_id"),
                idempotencyKey = row.getString("idempotency_key"),
                requestHash = row.getString("request_hash"),
                commandType = ChannelRolePolicyCommandType.valueOf(row.getString("command_type")),
                teamId = row.getLong("team_id"),
                channelId = row.getLong("channel_id"),
                roleId = row.getLong("role_id"),
                actorMembershipVersion = Instant.parse(row.getString("actor_membership_version")),
                permissions = row.getJsonObject("permissions"),
                result = row.getJsonObject("result"),
            )
        }
        return ProcessedCommandMatches(
            byOperationId = processed.firstOrNull { it.operationId == command.operationId },
            byIdempotencyKey = processed.firstOrNull {
                it.actorId == command.actorId && it.idempotencyKey == command.idempotencyKey
            },
        )
    }

    suspend fun insert(client: SqlClient, command: ChannelRolePolicyCommand, result: JsonObject) {
        client.preparedQuery(
            """
            INSERT INTO tb_channel_role_policy_command_inbox
                (operation_id, actor_id, idempotency_key, request_hash, command_type, team_id,
                 channel_id, role_id, actor_membership_version, permissions, result)
            VALUES (
                $1, $2, $3, $4, $5, $6,
                $7, $8, $9, $10::jsonb, $11::jsonb
            )
            """.trimIndent(),
        ).execute(
            Tuple.of(
                command.operationId,
                command.actorId,
                command.idempotencyKey,
                command.requestHash,
                command.commandType.name,
                command.teamId,
                command.channelId,
                command.roleId,
                command.actorMembershipVersion.toString(),
                command.permissions,
                result,
            ),
        ).coAwait()
    }

    suspend fun quarantine(record: CommandQuarantineRecord): Boolean = quarantine(pool, record)

    suspend fun quarantine(client: SqlClient, record: CommandQuarantineRecord): Boolean {
        val result = client.preparedQuery(
            """
            INSERT INTO tb_channel_role_policy_command_quarantine
                (topic, partition_id, record_offset, record_key, payload, reason)
            VALUES ($1, $2, $3, $4, $5, $6)
            ON CONFLICT (topic, partition_id, record_offset) DO NOTHING
            """.trimIndent(),
        ).execute(
            Tuple.of(record.topic, record.partition, record.offset, record.key, record.payload, record.reason),
        ).coAwait()
        return result.rowCount() == 1
    }
}
