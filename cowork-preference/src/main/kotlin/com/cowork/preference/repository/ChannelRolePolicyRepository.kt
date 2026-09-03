package com.cowork.preference.repository

import com.cowork.preference.domain.ChannelRolePolicyState
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

class ChannelRolePolicyRepository(private val pool: Pool) {

    suspend fun findPolicy(teamId: Long, channelId: Long, roleId: Long): ChannelRolePolicyState? =
        findPolicy(pool, teamId, channelId, roleId)

    internal suspend fun findPolicy(
        client: SqlClient,
        teamId: Long,
        channelId: Long,
        roleId: Long,
    ): ChannelRolePolicyState? {
        val rows = client.preparedQuery(
            """
            SELECT team_id, channel_id, role_id, permissions, state_occurred_at
            FROM tb_channel_role_policies
            WHERE team_id = ${'$'}1 AND channel_id = ${'$'}2 AND role_id = ${'$'}3
            """.trimIndent(),
        ).execute(Tuple.of(teamId, channelId, roleId)).coAwait()
        return rows.firstOrNull()?.toPolicyState()
    }

    internal suspend fun upsertPolicy(
        client: SqlClient,
        teamId: Long,
        channelId: Long,
        roleId: Long,
        permissions: JsonObject,
    ): ChannelRolePolicyState {
        val rows = client.preparedQuery(
            """
            WITH removed_tombstone AS (
                DELETE FROM tb_channel_role_policy_tombstones
                WHERE team_id = ${'$'}1 AND channel_id = ${'$'}2 AND role_id = ${'$'}3
                RETURNING state_occurred_at
            ),
            next_state AS (
                SELECT GREATEST(
                    clock_timestamp(),
                    COALESCE(
                        (SELECT state_occurred_at + INTERVAL '1 microsecond' FROM removed_tombstone),
                        '-infinity'::timestamptz
                    )
                ) AS state_occurred_at
            )
            INSERT INTO tb_channel_role_policies
                (team_id, channel_id, role_id, permissions, state_occurred_at)
            SELECT ${'$'}1, ${'$'}2, ${'$'}3, ${'$'}4::jsonb, state_occurred_at
            FROM next_state
            ON CONFLICT (team_id, channel_id, role_id)
            DO UPDATE SET
                permissions = EXCLUDED.permissions,
                state_occurred_at = GREATEST(
                    clock_timestamp(),
                    tb_channel_role_policies.state_occurred_at + INTERVAL '1 microsecond',
                    EXCLUDED.state_occurred_at
                ),
                updated_at = clock_timestamp()
            RETURNING team_id, channel_id, role_id, permissions, state_occurred_at
            """.trimIndent(),
        ).execute(Tuple.of(teamId, channelId, roleId, permissions)).coAwait()
        return rows.first().toPolicyState()
    }

    internal suspend fun deletePolicy(
        client: SqlClient,
        teamId: Long,
        channelId: Long,
        roleId: Long,
    ): ChannelRolePolicyState {
        val rows = client.preparedQuery(
            """
            WITH deleted_policy AS (
                DELETE FROM tb_channel_role_policies
                WHERE team_id = ${'$'}1 AND channel_id = ${'$'}2 AND role_id = ${'$'}3
                RETURNING state_occurred_at
            ),
            next_state AS (
                SELECT GREATEST(
                    clock_timestamp(),
                    COALESCE(
                        (SELECT state_occurred_at + INTERVAL '1 microsecond' FROM deleted_policy),
                        '-infinity'::timestamptz
                    ),
                    COALESCE(
                        (
                            SELECT state_occurred_at + INTERVAL '1 microsecond'
                            FROM tb_channel_role_policy_tombstones
                            WHERE team_id = ${'$'}1 AND channel_id = ${'$'}2 AND role_id = ${'$'}3
                        ),
                        '-infinity'::timestamptz
                    )
                ) AS state_occurred_at
            )
            INSERT INTO tb_channel_role_policy_tombstones
                (team_id, channel_id, role_id, state_occurred_at)
            SELECT ${'$'}1, ${'$'}2, ${'$'}3, state_occurred_at
            FROM next_state
            ON CONFLICT (team_id, channel_id, role_id)
            DO UPDATE SET
                state_occurred_at = GREATEST(
                    clock_timestamp(),
                    tb_channel_role_policy_tombstones.state_occurred_at + INTERVAL '1 microsecond',
                    EXCLUDED.state_occurred_at
                ),
                updated_at = clock_timestamp()
            RETURNING team_id, channel_id, role_id, NULL::jsonb AS permissions, state_occurred_at
            """.trimIndent(),
        ).execute(Tuple.of(teamId, channelId, roleId)).coAwait()
        return rows.first().toPolicyState()
    }

    internal suspend fun findPoliciesByRole(
        client: SqlClient,
        teamId: Long,
        roleId: Long,
    ): List<ChannelRolePolicyState> {
        val rows = client.preparedQuery(
            """
            SELECT team_id, channel_id, role_id, permissions, state_occurred_at
            FROM tb_channel_role_policies
            WHERE team_id = ${'$'}1 AND role_id = ${'$'}2
            ORDER BY channel_id
            FOR UPDATE
            """.trimIndent(),
        ).execute(Tuple.of(teamId, roleId)).coAwait()
        return rows.map { it.toPolicyState() }
    }

    internal suspend fun findPoliciesByTeam(client: SqlClient, teamId: Long): List<ChannelRolePolicyState> {
        val rows = client.preparedQuery(
            """
            SELECT team_id, channel_id, role_id, permissions, state_occurred_at
            FROM tb_channel_role_policies
            WHERE team_id = ${'$'}1
            ORDER BY channel_id, role_id
            FOR UPDATE
            """.trimIndent(),
        ).execute(Tuple.of(teamId)).coAwait()
        return rows.map { it.toPolicyState() }
    }

    internal suspend fun findPolicyPage(
        client: SqlClient,
        afterTeamId: Long,
        afterChannelId: Long,
        afterRoleId: Long,
        limit: Int,
    ): List<ChannelRolePolicyState> {
        val rows = client.preparedQuery(
            """
            WITH active_states AS (
                SELECT team_id, channel_id, role_id, permissions, state_occurred_at
                FROM tb_channel_role_policies
                WHERE (team_id, channel_id, role_id) > (${'$'}1, ${'$'}2, ${'$'}3)
                ORDER BY team_id, channel_id, role_id
                LIMIT ${'$'}4
                FOR SHARE
            ),
            deleted_states AS (
                SELECT team_id, channel_id, role_id, NULL::jsonb AS permissions, state_occurred_at
                FROM tb_channel_role_policy_tombstones
                WHERE (team_id, channel_id, role_id) > (${'$'}1, ${'$'}2, ${'$'}3)
                ORDER BY team_id, channel_id, role_id
                LIMIT ${'$'}4
                FOR SHARE
            ),
            states AS (
                SELECT team_id, channel_id, role_id, permissions, state_occurred_at FROM active_states
                UNION ALL
                SELECT team_id, channel_id, role_id, permissions, state_occurred_at FROM deleted_states
            )
            SELECT team_id, channel_id, role_id, permissions, state_occurred_at
            FROM states
            ORDER BY team_id, channel_id, role_id
            LIMIT ${'$'}4
            """.trimIndent(),
        ).execute(Tuple.of(afterTeamId, afterChannelId, afterRoleId, limit)).coAwait()
        return rows.map { it.toPolicyState() }
    }

    private fun Row.toPolicyState(): ChannelRolePolicyState = ChannelRolePolicyState(
        teamId = getLong("team_id"),
        channelId = getLong("channel_id"),
        roleId = getLong("role_id"),
        permissions = getJsonObject("permissions"),
        stateOccurredAt = getOffsetDateTime("state_occurred_at").toInstant(),
    )
}
