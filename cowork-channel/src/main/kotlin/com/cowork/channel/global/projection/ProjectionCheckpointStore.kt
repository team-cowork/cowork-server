package com.cowork.channel.global.projection

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class ProjectionCheckpointStore(
    private val jdbcTemplate: JdbcTemplate,
    private val topicIdentityProvider: ProjectionTopicIdentityProvider,
) {
    private val log = LoggerFactory.getLogger(ProjectionCheckpointStore::class.java)

    @Transactional
    fun initializeAssignment(
        stream: ProjectionStream,
        topicId: String,
        ranges: List<ProjectionPartitionRange>,
        assignedPartitions: Set<Int>,
    ): Map<Int, Long> {
        ranges.forEach { range ->
            jdbcTemplate.update(
                """
                INSERT INTO tb_kafka_projection_barriers
                    (consumer_group, topic_name, partition_id, topic_id, target_offset)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    target_offset = IF(
                        BINARY topic_id = BINARY VALUES(topic_id),
                        GREATEST(target_offset, VALUES(target_offset)),
                        VALUES(target_offset)
                    ),
                    topic_id = VALUES(topic_id),
                    captured_at = CURRENT_TIMESTAMP(6)
                """.trimIndent(),
                stream.consumerGroup,
                stream.topic,
                range.partition,
                topicId,
                range.endOffset,
            )
        }

        val existing = findCheckpoints(stream)
        val seeks = mutableMapOf<Int, Long>()
        ranges.filter { it.partition in assignedPartitions }.forEach { range ->
            val decision = ProjectionCatchUpPolicy.seekDecision(
                existing[range.partition],
                range.beginningOffset,
                range.endOffset,
                topicId,
            )
            if (decision.invalidCheckpointOffset != null) {
                log.error(
                    "Kafka projection checkpoint 범위 불일치: 수동 projection 재구성이 필요합니다 " +
                        "[group={}, topic={}, partition={}, checkpoint={}, beginning={}, end={}]",
                    stream.consumerGroup,
                    stream.topic,
                    range.partition,
                    decision.invalidCheckpointOffset,
                    range.beginningOffset,
                    range.endOffset,
                )
            }
            ensureCheckpointState(stream, range.partition, decision)
            seeks[range.partition] = decision.seekOffset
        }
        return seeks
    }

    fun advance(stream: ProjectionStream, partition: Int, nextOffset: Long, topicId: String) {
        val updated = jdbcTemplate.update(
            """
            UPDATE tb_kafka_projection_checkpoints
            SET next_offset = GREATEST(next_offset, ?),
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE consumer_group = ? AND topic_name = ? AND partition_id = ?
              AND BINARY topic_id = BINARY ?
            """.trimIndent(),
            nextOffset,
            stream.consumerGroup,
            stream.topic,
            partition,
            topicId,
        )
        check(updated == 1) {
            "Kafka projection checkpoint가 초기화되지 않았습니다: " +
                "${stream.consumerGroup}/${stream.topic}/$partition"
        }
    }

    fun markInvalidRecord(stream: ProjectionStream, partition: Int, recordOffset: Long, topicId: String) {
        val updated = jdbcTemplate.update(
            """
            UPDATE tb_kafka_projection_checkpoints
            SET recovery_snapshot_id = IF(
                    invalid_record_offset IS NULL OR ? > invalid_record_offset,
                    NULL,
                    recovery_snapshot_id
                ),
                invalid_record_offset = GREATEST(
                    COALESCE(invalid_record_offset, -1),
                    ?
                ),
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE consumer_group = ? AND topic_name = ? AND partition_id = ?
              AND BINARY topic_id = BINARY ?
            """.trimIndent(),
            recordOffset,
            recordOffset,
            stream.consumerGroup,
            stream.topic,
            partition,
            topicId,
        )
        check(updated == 1) {
            "Kafka projection checkpoint가 초기화되지 않았습니다: " +
                "${stream.consumerGroup}/${stream.topic}/$partition"
        }
    }

    fun markSnapshotCompleted(
        stream: ProjectionStream,
        partition: Int,
        markerOffset: Long,
        topicId: String,
        snapshotId: String,
    ) {
        val updated = jdbcTemplate.update(
            """
            UPDATE tb_kafka_projection_checkpoints
            SET invalid_record_offset = IF(
                    invalid_record_offset IS NOT NULL
                        AND ? > invalid_record_offset
                        AND recovery_snapshot_id IS NOT NULL
                        AND BINARY recovery_snapshot_id <> BINARY ?
                        AND (last_snapshot_id IS NULL OR BINARY last_snapshot_id <> BINARY ?),
                    NULL,
                    invalid_record_offset
                ),
                recovery_snapshot_id = CASE
                    WHEN invalid_record_offset IS NULL THEN NULL
                    WHEN ? <= invalid_record_offset THEN recovery_snapshot_id
                    WHEN last_snapshot_id IS NOT NULL AND BINARY last_snapshot_id = BINARY ?
                        THEN recovery_snapshot_id
                    WHEN recovery_snapshot_id IS NULL THEN ?
                    ELSE recovery_snapshot_id
                END,
                last_snapshot_id = IF(
                    snapshot_completed_offset IS NULL OR ? >= snapshot_completed_offset,
                    ?,
                    last_snapshot_id
                ),
                snapshot_completed_offset = GREATEST(COALESCE(snapshot_completed_offset, -1), ?),
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE consumer_group = ? AND topic_name = ? AND partition_id = ? AND BINARY topic_id = BINARY ?
            """.trimIndent(),
            markerOffset,
            snapshotId,
            snapshotId,
            markerOffset,
            snapshotId,
            snapshotId,
            markerOffset,
            snapshotId,
            markerOffset,
            stream.consumerGroup,
            stream.topic,
            partition,
            topicId,
        )
        check(updated == 1) {
            "Kafka projection snapshot marker의 topic generation이 일치하지 않습니다: " +
                "${stream.consumerGroup}/${stream.topic}/$partition"
        }
    }

    fun quarantine(stream: ProjectionStream, record: ConsumerRecord<String, String>, reason: String) {
        jdbcTemplate.update(
            """
            INSERT INTO tb_kafka_projection_quarantine
                (consumer_group, topic_name, partition_id, record_offset, record_key, payload, reason)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                record_key = VALUES(record_key),
                payload = VALUES(payload),
                reason = VALUES(reason),
                updated_at = CURRENT_TIMESTAMP(6)
            """.trimIndent(),
            stream.consumerGroup,
            record.topic(),
            record.partition(),
            record.offset(),
            record.key()?.take(512),
            record.value(),
            reason.take(1_000),
        )
    }

    fun isCaughtUp(requiredStreams: Set<ProjectionStream>): Boolean {
        val barriers = requiredStreams.associateWith(::findBarriers)
        val checkpoints = requiredStreams.associateWith(::findCheckpoints)
        val topicStates = topicIdentityProvider.topicStates(requiredStreams.mapTo(mutableSetOf()) { it.topic })
        return ProjectionCatchUpPolicy.isReady(requiredStreams, barriers, checkpoints) &&
            ProjectionCatchUpPolicy.matchesBrokerState(requiredStreams, barriers, checkpoints, topicStates)
    }

    private fun findBarriers(stream: ProjectionStream): Map<Int, ProjectionBarrier> = jdbcTemplate.query(
        """
        SELECT partition_id, topic_id, target_offset
        FROM tb_kafka_projection_barriers
        WHERE consumer_group = ? AND topic_name = ?
        """.trimIndent(),
        { resultSet, _ ->
            resultSet.getInt("partition_id") to ProjectionBarrier(
                topicId = resultSet.getString("topic_id"),
                targetOffset = resultSet.getLong("target_offset"),
            )
        },
        stream.consumerGroup,
        stream.topic,
    ).toMap()

    private fun findCheckpoints(stream: ProjectionStream): Map<Int, ProjectionCheckpoint> = jdbcTemplate.query(
        """
        SELECT partition_id, topic_id, next_offset, invalid_checkpoint_offset,
               snapshot_completed_offset, invalid_record_offset
        FROM tb_kafka_projection_checkpoints
        WHERE consumer_group = ? AND topic_name = ?
        """.trimIndent(),
        { resultSet, _ ->
            val invalidCheckpointOffset = resultSet.getLong("invalid_checkpoint_offset").let {
                if (resultSet.wasNull()) null else it
            }
            val snapshotCompletedOffset = resultSet.getLong("snapshot_completed_offset").let {
                if (resultSet.wasNull()) null else it
            }
            val invalidRecordOffset = resultSet.getLong("invalid_record_offset").let {
                if (resultSet.wasNull()) null else it
            }
            resultSet.getInt("partition_id") to ProjectionCheckpoint(
                nextOffset = resultSet.getLong("next_offset"),
                topicId = resultSet.getString("topic_id"),
                invalidCheckpointOffset = invalidCheckpointOffset,
                snapshotCompletedOffset = snapshotCompletedOffset,
                invalidRecordOffset = invalidRecordOffset,
            )
        },
        stream.consumerGroup,
        stream.topic,
    ).toMap()

    private fun ensureCheckpointState(stream: ProjectionStream, partition: Int, decision: ProjectionSeekDecision) {
        jdbcTemplate.update(
            """
            INSERT INTO tb_kafka_projection_checkpoints
                (consumer_group, topic_name, partition_id, topic_id, next_offset,
                 invalid_checkpoint_offset, snapshot_completed_offset, invalid_record_offset,
                 last_snapshot_id, recovery_snapshot_id)
            VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL)
            ON DUPLICATE KEY UPDATE
                snapshot_completed_offset = IF(
                    BINARY topic_id <> BINARY VALUES(topic_id),
                    NULL,
                    snapshot_completed_offset
                ),
                invalid_checkpoint_offset = IF(
                    BINARY topic_id <> BINARY VALUES(topic_id),
                    next_offset,
                    COALESCE(invalid_checkpoint_offset, VALUES(invalid_checkpoint_offset))
                ),
                invalid_record_offset = IF(
                    BINARY topic_id <> BINARY VALUES(topic_id),
                    NULL,
                    invalid_record_offset
                ),
                last_snapshot_id = IF(
                    BINARY topic_id <> BINARY VALUES(topic_id),
                    NULL,
                    last_snapshot_id
                ),
                recovery_snapshot_id = IF(
                    BINARY topic_id <> BINARY VALUES(topic_id),
                    NULL,
                    recovery_snapshot_id
                ),
                topic_id = VALUES(topic_id),
                updated_at = CURRENT_TIMESTAMP(6)
            """.trimIndent(),
            stream.consumerGroup,
            stream.topic,
            partition,
            decision.topicId,
            decision.seekOffset,
            decision.invalidCheckpointOffset,
        )
    }
}
