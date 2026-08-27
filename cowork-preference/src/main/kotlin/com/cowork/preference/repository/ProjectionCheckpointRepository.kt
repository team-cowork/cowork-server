package com.cowork.preference.repository

import com.cowork.preference.messaging.ProjectionCheckpointPolicy
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

data class ProjectionCheckpoint(
    val consumerGroup: String,
    val topic: String,
    val partition: Int,
    val nextOffset: Long,
    val topicId: String,
    val invalidCheckpointOffset: Long? = null,
    val invalidRecordOffset: Long? = null,
    val snapshotCompletedOffset: Long? = null,
)

data class ProjectionBarrier(val topicId: String, val targetOffset: Long)

data class ProjectionPartitionRange(val partition: Int, val beginningOffset: Long, val endOffset: Long)

data class ProjectionAssignmentResult(val seekOffsets: Map<Int, Long>, val invalidCheckpoint: Boolean)

data class QuarantinedProjectionRecord(val key: String?, val payload: String?, val reason: String)

class ProjectionCheckpointRepository(private val pool: Pool) {

    suspend fun load(consumerGroup: String, topic: String): Map<Int, ProjectionCheckpoint> = load(
        pool,
        consumerGroup,
        topic,
    )

    suspend fun loadBarriers(consumerGroup: String, topic: String): Map<Int, ProjectionBarrier> {
        val rows = pool.preparedQuery(
            """
            SELECT partition_id, topic_id, target_offset
            FROM tb_projection_consumer_barriers
            WHERE consumer_group = ${'$'}1 AND topic = ${'$'}2
            """.trimIndent(),
        ).execute(Tuple.of(consumerGroup, topic)).coAwait()
        return rows.associate { row ->
            row.getInteger("partition_id") to ProjectionBarrier(
                topicId = row.getString("topic_id"),
                targetOffset = row.getLong("target_offset"),
            )
        }
    }

    suspend fun initializeAssignment(
        consumerGroup: String,
        topic: String,
        topicId: String,
        ranges: List<ProjectionPartitionRange>,
        assignedPartitions: Set<Int>,
    ): ProjectionAssignmentResult = withTransaction(serializeOutboxWrites = false) { connection ->
        val existing = load(connection, consumerGroup, topic)
        val seekOffsets = mutableMapOf<Int, Long>()
        var invalidCheckpoint = false
        ranges.sortedBy { it.partition }.forEach { range ->
            val resolution = ProjectionCheckpointPolicy.resolve(
                stored = existing[range.partition],
                beginningOffset = range.beginningOffset,
                endOffset = range.endOffset,
                currentTopicId = topicId,
            )
            upsertBarrier(connection, consumerGroup, topic, topicId, range)
            ensureCheckpoint(connection, consumerGroup, topic, range.partition, resolution)
            if (resolution.invalidCheckpointOffset != null || existing[range.partition]?.invalidRecordOffset != null) {
                invalidCheckpoint = true
            }
            if (range.partition in assignedPartitions) seekOffsets[range.partition] = resolution.seekOffset
        }
        ProjectionAssignmentResult(seekOffsets, invalidCheckpoint)
    }

    suspend fun advance(checkpoint: ProjectionCheckpoint) {
        withTransaction(serializeOutboxWrites = false) { connection ->
            upsertMonotonic(connection, checkpoint)
        }
    }

    suspend fun completeSnapshot(checkpoint: ProjectionCheckpoint, markerOffset: Long) {
        withTransaction(serializeOutboxWrites = false) { connection ->
            val updated = connection.preparedQuery(
                """
                UPDATE tb_projection_consumer_checkpoints
                SET snapshot_completed_offset = GREATEST(COALESCE(snapshot_completed_offset, -1), ${'$'}1),
                    updated_at = now()
                WHERE consumer_group = ${'$'}2 AND topic = ${'$'}3 AND partition_id = ${'$'}4
                  AND topic_id = ${'$'}5
                """.trimIndent(),
            ).execute(
                Tuple.of(
                    markerOffset,
                    checkpoint.consumerGroup,
                    checkpoint.topic,
                    checkpoint.partition,
                    checkpoint.topicId,
                ),
            ).coAwait()
            check(updated.rowCount() == 1) { "projection snapshot topic generation mismatch" }
            upsertMonotonic(connection, checkpoint)
        }
    }

    suspend fun quarantineAndAdvance(checkpoint: ProjectionCheckpoint, record: QuarantinedProjectionRecord) {
        withTransaction(serializeOutboxWrites = false) { connection ->
            connection.preparedQuery(
                """
                INSERT INTO tb_projection_quarantine
                    (consumer_group, topic, partition_id, record_offset, record_key, payload, reason)
                VALUES (${'$'}1, ${'$'}2, ${'$'}3, ${'$'}4, ${'$'}5, ${'$'}6, ${'$'}7)
                ON CONFLICT (consumer_group, topic, partition_id, record_offset) DO NOTHING
                """.trimIndent(),
            ).execute(
                Tuple.of(
                    checkpoint.consumerGroup,
                    checkpoint.topic,
                    checkpoint.partition,
                    checkpoint.nextOffset - 1,
                    record.key,
                    record.payload,
                    record.reason,
                ),
            ).coAwait()
            latchInvalidRecordAndAdvance(connection, checkpoint)
        }
    }

    suspend fun inTransaction(checkpoint: ProjectionCheckpoint, applyProjection: suspend (SqlConnection) -> Unit) {
        withTransaction(serializeOutboxWrites = true) { connection ->
            applyProjection(connection)
            upsertMonotonic(connection, checkpoint)
        }
    }

    private suspend fun load(client: SqlClient, consumerGroup: String, topic: String): Map<Int, ProjectionCheckpoint> {
        val rows = client.preparedQuery(
            """
            SELECT partition_id, topic_id, next_offset, invalid_checkpoint_offset,
                   invalid_record_offset, snapshot_completed_offset
            FROM tb_projection_consumer_checkpoints
            WHERE consumer_group = ${'$'}1 AND topic = ${'$'}2
            """.trimIndent(),
        ).execute(Tuple.of(consumerGroup, topic)).coAwait()
        return rows.associate { row ->
            val partition = row.getInteger("partition_id")
            partition to ProjectionCheckpoint(
                consumerGroup = consumerGroup,
                topic = topic,
                partition = partition,
                nextOffset = row.getLong("next_offset"),
                topicId = row.getString("topic_id"),
                invalidCheckpointOffset = row.getLong("invalid_checkpoint_offset"),
                invalidRecordOffset = row.getLong("invalid_record_offset"),
                snapshotCompletedOffset = row.getLong("snapshot_completed_offset"),
            )
        }
    }

    private suspend fun upsertBarrier(
        connection: SqlConnection,
        consumerGroup: String,
        topic: String,
        topicId: String,
        range: ProjectionPartitionRange,
    ) {
        connection.preparedQuery(
            """
            INSERT INTO tb_projection_consumer_barriers
                (consumer_group, topic, partition_id, topic_id, target_offset)
            VALUES (${'$'}1, ${'$'}2, ${'$'}3, ${'$'}4, ${'$'}5)
            ON CONFLICT (consumer_group, topic, partition_id)
            DO UPDATE SET
                target_offset = CASE
                    WHEN tb_projection_consumer_barriers.topic_id = EXCLUDED.topic_id
                        THEN GREATEST(tb_projection_consumer_barriers.target_offset, EXCLUDED.target_offset)
                    ELSE EXCLUDED.target_offset
                END,
                topic_id = EXCLUDED.topic_id,
                captured_at = now()
            """.trimIndent(),
        ).execute(Tuple.of(consumerGroup, topic, range.partition, topicId, range.endOffset)).coAwait()
    }

    private suspend fun ensureCheckpoint(
        connection: SqlConnection,
        consumerGroup: String,
        topic: String,
        partition: Int,
        resolution: com.cowork.preference.messaging.ProjectionCheckpointResolution,
    ) {
        connection.preparedQuery(
            """
            INSERT INTO tb_projection_consumer_checkpoints
                (consumer_group, topic, partition_id, topic_id, next_offset,
                 invalid_checkpoint_offset, snapshot_completed_offset)
            VALUES (${'$'}1, ${'$'}2, ${'$'}3, ${'$'}4, ${'$'}5, ${'$'}6, NULL)
            ON CONFLICT (consumer_group, topic, partition_id)
            DO UPDATE SET
                snapshot_completed_offset = CASE
                    WHEN tb_projection_consumer_checkpoints.topic_id <> EXCLUDED.topic_id THEN NULL
                    ELSE tb_projection_consumer_checkpoints.snapshot_completed_offset
                END,
                invalid_checkpoint_offset = CASE
                    WHEN tb_projection_consumer_checkpoints.topic_id <> EXCLUDED.topic_id
                        THEN tb_projection_consumer_checkpoints.next_offset
                    ELSE COALESCE(
                        tb_projection_consumer_checkpoints.invalid_checkpoint_offset,
                        EXCLUDED.invalid_checkpoint_offset
                    )
                END,
                topic_id = EXCLUDED.topic_id,
                updated_at = now()
            """.trimIndent(),
        ).execute(
            Tuple.of(
                consumerGroup,
                topic,
                partition,
                resolution.topicId,
                resolution.seekOffset,
                resolution.invalidCheckpointOffset,
            ),
        ).coAwait()
    }

    private suspend fun upsertMonotonic(connection: SqlConnection, checkpoint: ProjectionCheckpoint) {
        val updated = connection.preparedQuery(
            """
            INSERT INTO tb_projection_consumer_checkpoints
                (consumer_group, topic, partition_id, topic_id, next_offset)
            VALUES (${'$'}1, ${'$'}2, ${'$'}3, ${'$'}4, ${'$'}5)
            ON CONFLICT (consumer_group, topic, partition_id)
            DO UPDATE SET
                next_offset = GREATEST(tb_projection_consumer_checkpoints.next_offset, EXCLUDED.next_offset),
                updated_at = now()
            WHERE tb_projection_consumer_checkpoints.topic_id = EXCLUDED.topic_id
            """.trimIndent(),
        ).execute(checkpoint.parameters()).coAwait()
        check(updated.rowCount() == 1) { "projection checkpoint topic generation mismatch" }
    }

    private suspend fun latchInvalidRecordAndAdvance(connection: SqlConnection, checkpoint: ProjectionCheckpoint) {
        val recordOffset = checkpoint.nextOffset - 1
        val updated = connection.preparedQuery(
            """
            UPDATE tb_projection_consumer_checkpoints
            SET next_offset = GREATEST(next_offset, ${'$'}1),
                invalid_record_offset = GREATEST(COALESCE(invalid_record_offset, -1), ${'$'}2),
                updated_at = now()
            WHERE consumer_group = ${'$'}3 AND topic = ${'$'}4 AND partition_id = ${'$'}5
              AND topic_id = ${'$'}6
            """.trimIndent(),
        ).execute(
            Tuple.of(
                checkpoint.nextOffset,
                recordOffset,
                checkpoint.consumerGroup,
                checkpoint.topic,
                checkpoint.partition,
                checkpoint.topicId,
            ),
        ).coAwait()
        check(updated.rowCount() == 1) { "projection checkpoint topic generation mismatch" }
    }

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

    private fun ProjectionCheckpoint.parameters(): Tuple = Tuple.of(
        consumerGroup,
        topic,
        partition,
        topicId,
        nextOffset,
    )
}
