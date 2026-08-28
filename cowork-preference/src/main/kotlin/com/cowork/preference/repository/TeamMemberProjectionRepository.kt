package com.cowork.preference.repository

import com.cowork.preference.domain.TeamMemberProjection
import com.cowork.preference.messaging.TeamMemberEvent
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import java.time.ZoneOffset

class TeamMemberProjectionRepository {
    suspend fun apply(client: SqlClient, event: TeamMemberEvent): Boolean {
        val rows = client.preparedQuery(
            """
            INSERT INTO tb_team_member_projections
                (team_id, account_id, built_in_role, deleted, source_occurred_at)
            VALUES (${'$'}1, ${'$'}2, ${'$'}3, ${'$'}4, ${'$'}5)
            ON CONFLICT (team_id, account_id)
            DO UPDATE SET
                built_in_role = EXCLUDED.built_in_role,
                deleted = EXCLUDED.deleted,
                source_occurred_at = EXCLUDED.source_occurred_at
            WHERE EXCLUDED.source_occurred_at > tb_team_member_projections.source_occurred_at
               OR (
                    EXCLUDED.source_occurred_at = tb_team_member_projections.source_occurred_at
                    AND EXCLUDED.deleted = TRUE
                    AND tb_team_member_projections.deleted = FALSE
               )
            """.trimIndent(),
        ).execute(
            Tuple.of(
                event.teamId,
                event.userId,
                event.role,
                event.eventType == "DELETE",
                event.occurredAt.atOffset(ZoneOffset.UTC),
            ),
        ).coAwait()
        return rows.rowCount() == 1
    }

    suspend fun find(client: SqlClient, teamId: Long, accountId: Long): TeamMemberProjection? {
        val rows = client.preparedQuery(
            """
            SELECT team_id, account_id, built_in_role, deleted, source_occurred_at
            FROM tb_team_member_projections
            WHERE team_id = ${'$'}1 AND account_id = ${'$'}2
            FOR SHARE
            """.trimIndent(),
        ).execute(Tuple.of(teamId, accountId)).coAwait()
        return rows.firstOrNull()?.toProjection()
    }

    private fun Row.toProjection() = TeamMemberProjection(
        teamId = getLong("team_id"),
        accountId = getLong("account_id"),
        builtInRole = getString("built_in_role"),
        deleted = getBoolean("deleted"),
        sourceOccurredAt = getOffsetDateTime("source_occurred_at").toInstant(),
    )
}
