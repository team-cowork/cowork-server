package com.cowork.preference.repository

import com.cowork.preference.messaging.PreferenceEvent
import com.cowork.preference.messaging.PreferenceEvents
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

class ProjectAccessStateRepository(private val outbox: PreferenceOutboxRepository) {
    suspend fun refresh(client: SqlClient, projectId: Long) {
        // Serialize recomputation so concurrent grants/revocations cannot publish an older decision last.
        client.preparedQuery("SELECT project_id FROM tb_project_lifecycle_projections WHERE project_id = \$1 FOR UPDATE")
            .execute(Tuple.of(projectId)).coAwait()
        val rows = client.preparedQuery(
            """
            WITH ranked AS (
                SELECT a.account_id, d.priority,
                       CASE WHEN jsonb_typeof(d.permissions -> 'read') = 'boolean' THEN (d.permissions ->> 'read')::boolean ELSE false END AS can_read,
                       CASE WHEN jsonb_typeof(d.permissions -> 'write') = 'boolean' THEN (d.permissions ->> 'write')::boolean ELSE false END AS can_write,
                       max(d.priority) OVER (PARTITION BY a.account_id) AS highest
                FROM account_project_role a
                JOIN project_role_definition d USING (project_id, role_name)
                JOIN tb_project_lifecycle_projections p ON p.project_id = a.project_id AND NOT p.deleted
                JOIN tb_team_member_projections m ON m.team_id = p.team_id AND m.account_id = a.account_id AND NOT m.deleted
                WHERE a.project_id = ${'$'}1
            ), effective AS (
                SELECT account_id, bool_and(can_read) AS can_read, bool_and(can_write) AS can_write
                FROM ranked WHERE priority = highest GROUP BY account_id
            ), accounts AS (
                SELECT account_id FROM effective
                UNION SELECT account_id FROM tb_project_access_states WHERE project_id = ${'$'}1
            )
            INSERT INTO tb_project_access_states (project_id, account_id, can_read, can_write, state_occurred_at)
            SELECT ${'$'}1, a.account_id, COALESCE(e.can_read, false), COALESCE(e.can_write, false), clock_timestamp()
            FROM accounts a LEFT JOIN effective e USING (account_id)
            ON CONFLICT (project_id, account_id) DO UPDATE SET
                can_read = EXCLUDED.can_read, can_write = EXCLUDED.can_write,
                state_occurred_at = GREATEST(clock_timestamp(), tb_project_access_states.state_occurred_at + INTERVAL '1 microsecond')
            WHERE tb_project_access_states.can_read IS DISTINCT FROM EXCLUDED.can_read
               OR tb_project_access_states.can_write IS DISTINCT FROM EXCLUDED.can_write
            RETURNING project_id, account_id, can_read, can_write, state_occurred_at
            """.trimIndent(),
        ).execute(Tuple.of(projectId)).coAwait()
        rows.forEach { outbox.enqueue(client, event(it)) }
    }

    suspend fun removeTeamAssignments(client: SqlClient, teamId: Long, accountId: Long?) {
        val rows = client.preparedQuery(
            """
            DELETE FROM account_project_role a USING tb_project_lifecycle_projections p
            WHERE p.project_id = a.project_id AND p.team_id = ${'$'}1
              AND (${'$'}2::bigint IS NULL OR a.account_id = ${'$'}2)
            RETURNING a.project_id
            """.trimIndent(),
        ).execute(Tuple.of(teamId, accountId)).coAwait()
        rows.map { it.getLong("project_id") }.distinct().forEach { refresh(client, it) }
    }

    suspend fun publishSnapshot(client: SqlClient) {
        val projects = client.query(
            "SELECT project_id FROM project_role_definition UNION SELECT project_id FROM tb_project_access_states ORDER BY project_id",
        ).execute().coAwait()
        projects.forEach { refresh(client, it.getLong("project_id")) }
        val states = client.query("SELECT * FROM tb_project_access_states ORDER BY project_id, account_id").execute().coAwait()
        states.forEach { outbox.enqueue(client, event(it)) }
    }

    private fun event(row: Row): PreferenceEvent {
        val projectId = row.getLong("project_id")
        val accountId = row.getLong("account_id")
        val version = row.getOffsetDateTime("state_occurred_at").toInstant()
        return PreferenceEvent(
            PreferenceEvents.PROJECT_ACCESS_STATE_TOPIC,
            "$projectId:$accountId",
            JsonObject().put("eventType", "UPSERT")
                .put("projectId", projectId).put("accountId", accountId)
                .put("read", row.getBoolean("can_read")).put("write", row.getBoolean("can_write"))
                .put("occurredAt", version.toString()),
            version,
        )
    }
}
