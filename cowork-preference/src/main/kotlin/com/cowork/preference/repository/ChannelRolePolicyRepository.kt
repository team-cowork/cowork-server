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

    /**
     * 팀 OWNER가 아닌 계정이 채널 메시지를 읽을 수 있는지 계산한다.
     * cowork-channel의 `ChannelMessageReadPolicyEvaluator`와 같이 계정에 할당된 역할 중 이 채널의 정책이 있는
     * 가장 높은 priority의 정책이 모두 `message_read`일 때만 허용하며, 적용 가능한 정책이 없으면 거부한다.
     * `message_read` 키가 없는 정책은 이 권한에 의견이 없으므로 평가에서 제외한다.
     * 정책·역할·할당은 이 서비스가 소유하므로 로컬 테이블만 읽는다.
     */
    suspend fun canReadMessages(teamId: Long, accountId: Long, channelId: Long): Boolean {
        val rows = pool.preparedQuery(
            """
            WITH applicable AS (
                SELECT d.priority AS priority, (p.permissions ->> 'message_read')::boolean AS message_read
                FROM tb_account_team_roles a
                JOIN tb_team_role_definitions d ON d.id = a.role_id AND d.team_id = a.team_id
                JOIN tb_channel_role_policies p ON p.team_id = a.team_id AND p.role_id = a.role_id
                WHERE a.account_id = ${'$'}1 AND a.team_id = ${'$'}2 AND p.channel_id = ${'$'}3
                  AND p.permissions ? 'message_read'
            )
            SELECT COALESCE(
                (
                    SELECT bool_and(message_read)
                    FROM applicable
                    WHERE priority = (SELECT max(priority) FROM applicable)
                ),
                FALSE
            ) AS readable
            """.trimIndent(),
        ).execute(Tuple.of(accountId, teamId, channelId)).coAwait()
        return rows.first().getBoolean("readable")
    }

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

    internal suspend fun findPoliciesByChannel(
        client: SqlClient,
        teamId: Long,
        channelId: Long,
    ): List<ChannelRolePolicyState> {
        val rows = client.preparedQuery(
            """
            SELECT team_id, channel_id, role_id, permissions, state_occurred_at
            FROM tb_channel_role_policies
            WHERE team_id = ${'$'}1 AND channel_id = ${'$'}2
            ORDER BY role_id
            FOR UPDATE
            """.trimIndent(),
        ).execute(Tuple.of(teamId, channelId)).coAwait()
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
