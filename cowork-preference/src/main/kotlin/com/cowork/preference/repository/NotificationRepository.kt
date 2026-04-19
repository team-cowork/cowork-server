package com.cowork.preference.repository

import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Tuple
import io.vertx.core.json.JsonObject

class NotificationRepository(private val pool: Pool) {

    suspend fun findNotification(accountId: Long, channelId: Long): JsonObject? {
        val rows = pool.preparedQuery(
            "SELECT settings FROM account_channel_notification WHERE account_id = \$1 AND channel_id = \$2"
        ).execute(Tuple.of(accountId, channelId)).coAwait()
        return rows.firstOrNull()?.getJsonObject("settings")
    }

    suspend fun upsertNotification(accountId: Long, channelId: Long, settings: JsonObject) {
        pool.preparedQuery(
            """
            INSERT INTO account_channel_notification (account_id, channel_id, settings)
            VALUES (${'$'}1, ${'$'}2, ${'$'}3::jsonb)
            ON CONFLICT (account_id, channel_id)
            DO UPDATE SET settings = EXCLUDED.settings
            """.trimIndent()
        ).execute(Tuple.of(accountId, channelId, settings)).coAwait()
    }
}
