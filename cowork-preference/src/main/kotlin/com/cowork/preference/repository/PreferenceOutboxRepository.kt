package com.cowork.preference.repository

import com.cowork.preference.messaging.PreferenceEvent
import com.cowork.preference.messaging.PreferenceEvents
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import java.time.Instant
import java.time.ZoneOffset

enum class PreferenceOutboxDispatchResult {
    PUBLISHED,
    EMPTY,
    BUSY,
}

class PreferenceOutboxRepository(private val pool: Pool) {

    suspend fun <T> inTransaction(block: suspend (SqlConnection) -> T): T = withTransaction(
        serializeOutboxWrites = true,
        block = block,
    )

    suspend fun enqueue(client: SqlClient, event: PreferenceEvent) {
        require(event.topic in PreferenceEvents.OUTBOX_TOPICS) { "unsupported outbox topic '${event.topic}'" }
        require(event.key.isNotBlank()) { "record key must not be blank" }
        client.preparedQuery(
            """
            INSERT INTO tb_preference_event_outbox
                (topic, record_key, payload, occurred_at, partition_id)
            VALUES (${'$'}1, ${'$'}2, ${'$'}3::jsonb, ${'$'}4, ${'$'}5)
            """.trimIndent(),
        ).execute(
            Tuple.of(
                event.topic,
                event.key,
                event.payload.encode(),
                event.occurredAt.atOffset(ZoneOffset.UTC),
                event.partition,
            ),
        ).coAwait()
    }

    suspend fun enqueueAll(client: SqlClient, events: Iterable<PreferenceEvent>) {
        events.forEach { enqueue(client, it) }
    }

    suspend fun dispatchNextIfLeader(publish: suspend (PreferenceEvent) -> Unit): PreferenceOutboxDispatchResult =
        withTransaction(serializeOutboxWrites = false) { connection ->
            if (!acquireDispatchLock(connection)) return@withTransaction PreferenceOutboxDispatchResult.BUSY
            val row = loadNext(connection) ?: return@withTransaction PreferenceOutboxDispatchResult.EMPTY
            val event = row.toPreferenceEvent()
            publish(event)
            val updated = connection.preparedQuery(
                """
                UPDATE tb_preference_event_outbox
                SET published_at = now()
                WHERE id = ${'$'}1 AND published_at IS NULL
                """.trimIndent(),
            ).execute(Tuple.of(row.getLong("id"))).coAwait()
            check(updated.rowCount() == 1) { "outbox record was concurrently published" }
            PreferenceOutboxDispatchResult.PUBLISHED
        }

    suspend fun deletePublishedBefore(cutoff: Instant): Long = pool.preparedQuery(
        """
        DELETE FROM tb_preference_event_outbox
        WHERE published_at < ${'$'}1
        """.trimIndent(),
    ).execute(Tuple.of(cutoff.atOffset(ZoneOffset.UTC))).coAwait().rowCount().toLong()

    private suspend fun acquireDispatchLock(connection: SqlConnection): Boolean {
        val rows = connection.preparedQuery(
            "SELECT pg_try_advisory_xact_lock(${'$'}1) AS acquired",
        ).execute(Tuple.of(PreferenceOutboxOrdering.LOCK_ID)).coAwait()
        return rows.first().getBoolean("acquired")
    }

    private suspend fun loadNext(connection: SqlConnection): Row? {
        val rows = connection.preparedQuery(
            """
            SELECT id, topic, partition_id, record_key, payload::text AS payload, occurred_at
            FROM tb_preference_event_outbox
            WHERE published_at IS NULL
            ORDER BY id
            LIMIT 1
            FOR UPDATE
            """.trimIndent(),
        ).execute().coAwait()
        return rows.firstOrNull()
    }

    private fun Row.toPreferenceEvent(): PreferenceEvent = PreferenceEvent(
        topic = getString("topic"),
        key = getString("record_key"),
        payload = JsonObject(getString("payload")),
        occurredAt = getOffsetDateTime("occurred_at").toInstant(),
        partition = getInteger("partition_id"),
    )

    private suspend fun <T> withTransaction(serializeOutboxWrites: Boolean, block: suspend (SqlConnection) -> T): T {
        val connection = pool.connection.coAwait()
        try {
            val transaction = connection.begin().coAwait()
            try {
                if (serializeOutboxWrites) PreferenceOutboxOrdering.acquireForWrite(connection)
                val result = block(connection)
                transaction.commit().coAwait()
                return result
            } catch (error: Throwable) {
                runCatching { transaction.rollback().coAwait() }
                    .onFailure(error::addSuppressed)
                throw error
            }
        } finally {
            connection.close().coAwait()
        }
    }
}
