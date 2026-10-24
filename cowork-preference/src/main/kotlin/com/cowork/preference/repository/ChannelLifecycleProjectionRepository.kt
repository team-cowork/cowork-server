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
                (channel_id, team_id, deleted, is_private, source_occurred_at, channel_type)
            VALUES (${'$'}1, ${'$'}2, ${'$'}3, ${'$'}4, ${'$'}5, ${'$'}6)
            ON CONFLICT (channel_id)
            DO UPDATE SET
                team_id = EXCLUDED.team_id,
                deleted = EXCLUDED.deleted,
                is_private = EXCLUDED.is_private,
                channel_type = COALESCE(EXCLUDED.channel_type, tb_channel_lifecycle_projections.channel_type),
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
                    -- is_private 도입 전에 저장된 행은 같은 version의 snapshot으로 값을 채운다.
                    OR (
                        EXCLUDED.source_occurred_at = tb_channel_lifecycle_projections.source_occurred_at
                        AND NOT EXCLUDED.deleted
                        AND NOT tb_channel_lifecycle_projections.deleted
                        AND ((tb_channel_lifecycle_projections.is_private IS NULL AND EXCLUDED.is_private IS NOT NULL)
                             OR (tb_channel_lifecycle_projections.channel_type IS NULL AND EXCLUDED.channel_type IS NOT NULL))
                    )
              )
            """.trimIndent(),
        ).execute(
            Tuple.of(
                event.channelId,
                event.teamId,
                event.deleted,
                event.isPrivate,
                event.occurredAt.atOffset(ZoneOffset.UTC),
                event.channelType,
            ),
        ).coAwait()
        return rows.rowCount() == 1
    }

    suspend fun findForUpdate(client: SqlClient, channelId: Long): ChannelLifecycleProjection? {
        val rows = client.preparedQuery(
            """
            SELECT channel_id, team_id, deleted, is_private, source_occurred_at, channel_type
            FROM tb_channel_lifecycle_projections
            WHERE channel_id = ${'$'}1
            FOR UPDATE
            """.trimIndent(),
        ).execute(Tuple.of(channelId)).coAwait()
        return rows.firstOrNull()?.toProjection()
    }

    /** 트랜잭션 밖의 단순 조회(authorization guard 등)에 사용한다. 잠금이 없어 projection consumer의 갱신 트랜잭션을 기다리지 않는다. */
    suspend fun findForRead(client: SqlClient, channelId: Long): ChannelLifecycleProjection? {
        val rows = client.preparedQuery(
            """
            SELECT channel_id, team_id, deleted, is_private, source_occurred_at, channel_type
            FROM tb_channel_lifecycle_projections
            WHERE channel_id = ${'$'}1
            """.trimIndent(),
        ).execute(Tuple.of(channelId)).coAwait()
        return rows.firstOrNull()?.toProjection()
    }

    private fun Row.toProjection() = ChannelLifecycleProjection(
        channelId = getLong("channel_id"),
        teamId = getLong("team_id"),
        deleted = getBoolean("deleted"),
        isPrivate = getBoolean("is_private"),
        channelType = getString("channel_type"),
        sourceOccurredAt = getOffsetDateTime("source_occurred_at").toInstant(),
    )
}
