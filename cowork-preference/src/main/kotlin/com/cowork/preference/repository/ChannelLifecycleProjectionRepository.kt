package com.cowork.preference.repository

import com.cowork.preference.domain.ChannelLifecycleProjection
import com.cowork.preference.messaging.ChannelLifecycleEvent
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import java.time.ZoneOffset

class ChannelLifecycleProjectionRepository {
    /**
     * 높은 source version을 우선하고 같은 version에서는 삭제를 우선한다. 삭제된 채널은 되살아나지 않으므로
     * 이후 도착한 active state는 version과 무관하게 삭제 fence를 덮어쓰지 못한다.
     */
    suspend fun apply(client: SqlClient, event: ChannelLifecycleEvent): Boolean {
        val rows = client.preparedQuery(
            """
            INSERT INTO tb_channel_lifecycle_projections
                (channel_id, team_id, deleted, source_occurred_at)
            VALUES (${'$'}1, ${'$'}2, ${'$'}3, ${'$'}4)
            ON CONFLICT (channel_id)
            DO UPDATE SET
                team_id = EXCLUDED.team_id,
                deleted = EXCLUDED.deleted,
                source_occurred_at = EXCLUDED.source_occurred_at,
                updated_at = now()
            WHERE (EXCLUDED.deleted OR NOT tb_channel_lifecycle_projections.deleted)
              AND (
                    EXCLUDED.source_occurred_at > tb_channel_lifecycle_projections.source_occurred_at
                    OR (
                        EXCLUDED.source_occurred_at = tb_channel_lifecycle_projections.source_occurred_at
                        AND EXCLUDED.deleted
                        AND NOT tb_channel_lifecycle_projections.deleted
                    )
              )
            """.trimIndent(),
        ).execute(
            Tuple.of(
                event.channelId,
                event.teamId,
                event.deleted,
                event.occurredAt.atOffset(ZoneOffset.UTC),
            ),
        ).coAwait()
        return rows.rowCount() == 1
    }

    suspend fun findForShare(client: SqlClient, channelId: Long): ChannelLifecycleProjection? {
        val rows = client.preparedQuery(
            """
            SELECT channel_id, team_id, deleted, source_occurred_at
            FROM tb_channel_lifecycle_projections
            WHERE channel_id = ${'$'}1
            FOR SHARE
            """.trimIndent(),
        ).execute(Tuple.of(channelId)).coAwait()
        return rows.firstOrNull()?.toProjection()
    }

    private fun Row.toProjection() = ChannelLifecycleProjection(
        channelId = getLong("channel_id"),
        teamId = getLong("team_id"),
        deleted = getBoolean("deleted"),
        sourceOccurredAt = getOffsetDateTime("source_occurred_at").toInstant(),
    )
}
