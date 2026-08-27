package com.cowork.preference.repository

import com.cowork.preference.domain.ChannelNotificationPreference
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

class NotificationRepository(private val pool: Pool) {

    suspend fun findNotification(accountId: Long, channelId: Long): JsonObject? {
        val rows = pool.preparedQuery(
            "SELECT settings FROM account_channel_notification WHERE account_id = \$1 AND channel_id = \$2",
        ).execute(Tuple.of(accountId, channelId)).coAwait()
        return rows.firstOrNull()?.getJsonObject("settings")
    }

    internal suspend fun upsertNotification(
        client: SqlClient,
        accountId: Long,
        channelId: Long,
        settings: JsonObject,
    ): ChannelNotificationPreference {
        val rows = client.preparedQuery(
            """
            INSERT INTO account_channel_notification (account_id, channel_id, settings)
            VALUES (${'$'}1, ${'$'}2, ${'$'}3::jsonb)
            ON CONFLICT (account_id, channel_id)
            DO UPDATE SET settings = EXCLUDED.settings
            RETURNING account_id, channel_id, settings, state_occurred_at
            """.trimIndent(),
        ).execute(Tuple.of(accountId, channelId, settings)).coAwait()
        return rows.first().toChannelNotificationPreference()
    }

    internal suspend fun findNotificationPage(
        client: SqlClient,
        afterAccountId: Long,
        afterChannelId: Long,
        limit: Int,
    ): List<ChannelNotificationPreference> {
        val rows = client.preparedQuery(
            """
            SELECT account_id, channel_id, settings, state_occurred_at
            FROM account_channel_notification
            WHERE (account_id, channel_id) > (${'$'}1, ${'$'}2)
            ORDER BY account_id, channel_id
            LIMIT ${'$'}3
            FOR SHARE
            """.trimIndent(),
        ).execute(Tuple.of(afterAccountId, afterChannelId, limit)).coAwait()
        return rows.map { it.toChannelNotificationPreference() }
    }

    private fun io.vertx.sqlclient.Row.toChannelNotificationPreference() = ChannelNotificationPreference(
        accountId = getLong("account_id"),
        channelId = getLong("channel_id"),
        notification = getJsonObject("settings")?.getBoolean("notification", true) ?: true,
        stateOccurredAt = getOffsetDateTime("state_occurred_at"),
    )
}
