package com.cowork.preference.repository

import com.cowork.preference.messaging.DmParticipantEvent
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import java.time.ZoneOffset

class DmParticipantProjectionRepository {
    /** 높은 source version을 우선하고 같은 version에서는 삭제를 우선한다. 반영되었으면 true를 반환한다. */
    suspend fun apply(client: SqlClient, event: DmParticipantEvent): Boolean {
        val rows = client.preparedQuery(
            """
            INSERT INTO tb_dm_participant_projections (channel_id, account_id, deleted, source_occurred_at)
            VALUES (${'$'}1, ${'$'}2, ${'$'}3, ${'$'}4)
            ON CONFLICT (channel_id, account_id)
            DO UPDATE SET
                deleted = EXCLUDED.deleted,
                source_occurred_at = EXCLUDED.source_occurred_at
            WHERE EXCLUDED.source_occurred_at > tb_dm_participant_projections.source_occurred_at
               OR (
                    EXCLUDED.source_occurred_at = tb_dm_participant_projections.source_occurred_at
                    AND EXCLUDED.deleted = TRUE
                    AND tb_dm_participant_projections.deleted = FALSE
               )
            """.trimIndent(),
        ).execute(
            Tuple.of(event.channelId, event.userId, event.deleted, event.occurredAt.atOffset(ZoneOffset.UTC)),
        ).coAwait()
        return rows.rowCount() == 1
    }

    /** authorization guard용 단순 조회다. 잠금이 없어 projection consumer의 갱신 트랜잭션을 기다리지 않는다. */
    suspend fun isActiveParticipant(client: SqlClient, channelId: Long, accountId: Long): Boolean {
        val rows = client.preparedQuery(
            "SELECT deleted FROM tb_dm_participant_projections WHERE channel_id = ${'$'}1 AND account_id = ${'$'}2",
        ).execute(Tuple.of(channelId, accountId)).coAwait()
        return rows.firstOrNull()?.getBoolean("deleted") == false
    }
}
