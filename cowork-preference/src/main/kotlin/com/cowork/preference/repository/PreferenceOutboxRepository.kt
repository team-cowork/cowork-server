package com.cowork.preference.repository

import com.cowork.preference.messaging.PreferenceEvent
import com.cowork.preference.messaging.PreferenceEvents
import io.vertx.core.json.Json
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

    // TODO(#596): 2026-11-01 05:00 KST 이후 아래 조건 완료를 확인하고 제거한다.
    // 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
    // 저장 outbox 보정과 구형 producer·재처리·롤백 종료 확인 후 제거한다.
    internal suspend fun repairLegacyTeamRoleRecords(client: SqlClient): Int = client.preparedQuery(
        """
        WITH bodies AS (
            SELECT id, topic, CASE WHEN jsonb_typeof(payload) = 'string'
                THEN (payload #>> '{}')::jsonb ELSE payload END AS body
            FROM tb_preference_event_outbox WHERE topic IN (${'$'}1, ${'$'}2)
        ), legacy AS (
            SELECT b.id, CASE WHEN b.topic = ${'$'}1
                THEN jsonb_set(body, '{role,isDefault}', to_jsonb(COALESCE(d.is_default, false)))
                ELSE jsonb_set(body, '{isDefault}', to_jsonb(COALESCE(d.is_default, false))) END AS repaired
            FROM bodies b LEFT JOIN tb_team_role_definitions d
                ON d.id = (CASE WHEN b.topic = ${'$'}1 THEN body #>> '{role,id}' ELSE body ->> 'roleId' END)::bigint
                AND d.team_id = (body ->> 'teamId')::bigint
            WHERE (b.topic = ${'$'}1 AND jsonb_typeof(body -> 'role') = 'object'
                AND (NOT (body -> 'role' ? 'isDefault') OR (d.is_default AND body #> '{role,isDefault}' = 'false'::jsonb)))
               OR (b.topic = ${'$'}2 AND body ->> 'eventType' = 'ROLE_UPSERTED'
                AND (NOT (body ? 'isDefault') OR (d.is_default AND body -> 'isDefault' = 'false'::jsonb)))
            ORDER BY b.id LIMIT 1000
        )
        UPDATE tb_preference_event_outbox o SET payload = legacy.repaired FROM legacy WHERE o.id = legacy.id
        """.trimIndent(),
    ).execute(Tuple.of(PreferenceEvents.TEAM_ROLE_COMMAND_RESULT_TOPIC, PreferenceEvents.TEAM_ROLE_TOPIC)).coAwait().rowCount()

    suspend fun <T> inTransaction(block: suspend (SqlConnection) -> T): T = withPooledTransaction(
        serializeOutboxWrites = true,
        block = block,
    )

    suspend fun <T> inTransaction(connection: SqlConnection, block: suspend (SqlConnection) -> T): T =
        withTransaction(connection, serializeOutboxWrites = true, block)

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
                event.payload,
                event.occurredAt.atOffset(ZoneOffset.UTC),
                event.partition,
            ),
        ).coAwait()
    }

    suspend fun enqueueAll(client: SqlClient, events: Iterable<PreferenceEvent>) {
        events.forEach { enqueue(client, it) }
    }

    /**
     * 한 DB session이 전체 snapshot run 동안 lock을 소유한다. 고정 TTL lease와 달리 긴 page scan도
     * 다른 replica와 겹치지 않으며, process/connection 장애 시 PostgreSQL이 lock을 회수한다.
     */
    suspend fun withProjectionSnapshotLock(block: suspend (SqlConnection) -> Unit): Boolean {
        val connection = pool.connection.coAwait()
        var acquired = false
        var primaryFailure: Throwable? = null
        try {
            acquired = acquireProjectionSnapshotLock(connection)
            if (!acquired) return false
            block(connection)
            return true
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            if (acquired) {
                try {
                    releaseProjectionSnapshotLock(connection)
                } catch (error: Throwable) {
                    cleanupFailure = error
                }
            }
            try {
                connection.close().coAwait()
            } catch (error: Throwable) {
                if (cleanupFailure == null) cleanupFailure = error else cleanupFailure.addSuppressed(error)
            }
            if (cleanupFailure != null) {
                if (primaryFailure == null) throw cleanupFailure
                primaryFailure.addSuppressed(cleanupFailure)
            }
        }
    }

    suspend fun dispatchNextIfLeader(publish: suspend (PreferenceEvent) -> Unit): PreferenceOutboxDispatchResult =
        withPooledTransaction(serializeOutboxWrites = false) { connection ->
            if (!acquireDispatchLock(connection)) return@withPooledTransaction PreferenceOutboxDispatchResult.BUSY
            val row = loadNext(connection) ?: return@withPooledTransaction PreferenceOutboxDispatchResult.EMPTY
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

    private suspend fun acquireProjectionSnapshotLock(connection: SqlConnection): Boolean {
        val rows = connection.preparedQuery(
            "SELECT pg_try_advisory_lock(${'$'}1) AS acquired",
        ).execute(Tuple.of(PROJECTION_SNAPSHOT_LOCK_ID)).coAwait()
        return rows.first().getBoolean("acquired")
    }

    private suspend fun releaseProjectionSnapshotLock(connection: SqlConnection) {
        val rows = connection.preparedQuery(
            "SELECT pg_advisory_unlock(${'$'}1) AS released",
        ).execute(Tuple.of(PROJECTION_SNAPSHOT_LOCK_ID)).coAwait()
        check(rows.first().getBoolean("released")) { "Preference projection snapshot lock was not owned" }
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
        payload = decodePayload(getString("payload")),
        occurredAt = getOffsetDateTime("occurred_at").toInstant(),
        partition = getInteger("partition_id"),
    )

    private fun decodePayload(payload: String): JsonObject {
        val json = payload.trim()
        return if (json.startsWith('"')) {
            JsonObject(Json.decodeValue(json, String::class.java))
        } else {
            JsonObject(json)
        }
    }

    private suspend fun <T> withPooledTransaction(
        serializeOutboxWrites: Boolean,
        block: suspend (SqlConnection) -> T,
    ): T {
        val connection = pool.connection.coAwait()
        try {
            return withTransaction(connection, serializeOutboxWrites, block)
        } finally {
            connection.close().coAwait()
        }
    }

    private suspend fun <T> withTransaction(
        connection: SqlConnection,
        serializeOutboxWrites: Boolean,
        block: suspend (SqlConnection) -> T,
    ): T {
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
    }

    private companion object {
        const val PROJECTION_SNAPSHOT_LOCK_ID = 0x434F574F524B5053L
    }
}
