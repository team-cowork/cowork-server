package com.cowork.roadmap.global.team;

import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class ProjectionCheckpointRepository {

    private static final String ENSURE_CHECKPOINT_SQL = """
            INSERT INTO tb_kafka_projection_offsets
                (consumer_group, topic_name, partition_id, topic_id, next_offset,
                 invalid_checkpoint_offset, snapshot_completed_offset, invalid_record_offset,
                 last_snapshot_id, recovery_snapshot_id)
            VALUES (:consumerGroup, :topic, :partition, :topicId, :nextOffset, :invalidOffset,
                    NULL, NULL, NULL, NULL)
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
            """;

    private final JdbcClient jdbcClient;

    @Transactional
    public Map<Integer, Long> initializeAssignment(String consumerGroup,
            String topic,
            String topicId,
            List<ProjectionPartitionRange> ranges,
            Set<Integer> assignedPartitions) {
        Map<Integer, ProjectionCheckpoint> existing = findByTopic(consumerGroup, topic);
        Map<Integer, Long> offsets = new HashMap<>();
        for (ProjectionPartitionRange range : ranges) {
            ProjectionSeekDecision decision = ProjectionCatchUpPolicy
                    .seekDecision(existing.get(range.partition()), range.beginningOffset(), range.endOffset(), topicId);
            upsertBarrier(consumerGroup, topic, topicId, range);
            ensureCheckpoint(consumerGroup, topic, range.partition(), decision);
            if (assignedPartitions.contains(range.partition())) {
                offsets.put(range.partition(), decision.seekOffset());
            }
        }
        return offsets;
    }

    public void advance(String consumerGroup, String topic, int partition, long nextOffset, String topicId) {
        int updated = jdbcClient.sql("""
                UPDATE tb_kafka_projection_offsets
                SET next_offset = GREATEST(next_offset, :nextOffset),
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE consumer_group = :consumerGroup AND topic_name = :topic
                  AND partition_id = :partition AND BINARY topic_id = BINARY :topicId
                """)
                .param("consumerGroup", consumerGroup)
                .param("topic", topic)
                .param("partition", partition)
                .param("nextOffset", nextOffset)
                .param("topicId", topicId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Kafka projection checkpoint generation mismatch");
        }
    }

    public void markInvalidRecord(String consumerGroup,
            String topic,
            int partition,
            long recordOffset,
            String topicId) {
        int updated = jdbcClient.sql("""
                UPDATE tb_kafka_projection_offsets
                SET recovery_snapshot_id = IF(
                        invalid_record_offset IS NULL OR :recordOffset > invalid_record_offset,
                        NULL,
                        recovery_snapshot_id
                    ),
                    invalid_record_offset = GREATEST(
                        COALESCE(invalid_record_offset, -1),
                        :recordOffset
                    ),
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE consumer_group = :consumerGroup AND topic_name = :topic
                  AND partition_id = :partition AND BINARY topic_id = BINARY :topicId
                """)
                .param("consumerGroup", consumerGroup)
                .param("topic", topic)
                .param("partition", partition)
                .param("recordOffset", recordOffset)
                .param("topicId", topicId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Kafka projection checkpoint generation mismatch");
        }
    }

    public void markSnapshotCompleted(String consumerGroup,
            String topic,
            int partition,
            long markerOffset,
            String topicId,
            String snapshotId) {
        int updated = jdbcClient.sql("""
                UPDATE tb_kafka_projection_offsets
                SET invalid_record_offset = IF(
                        invalid_record_offset IS NOT NULL
                            AND :markerOffset > invalid_record_offset
                            AND recovery_snapshot_id IS NOT NULL
                            AND BINARY recovery_snapshot_id <> BINARY :snapshotId
                            AND (last_snapshot_id IS NULL OR BINARY last_snapshot_id <> BINARY :snapshotId),
                        NULL,
                        invalid_record_offset
                    ),
                    recovery_snapshot_id = CASE
                        WHEN invalid_record_offset IS NULL THEN NULL
                        WHEN :markerOffset <= invalid_record_offset THEN recovery_snapshot_id
                        WHEN last_snapshot_id IS NOT NULL
                            AND BINARY last_snapshot_id = BINARY :snapshotId
                            THEN recovery_snapshot_id
                        WHEN recovery_snapshot_id IS NULL THEN :snapshotId
                        ELSE recovery_snapshot_id
                    END,
                    last_snapshot_id = IF(
                        snapshot_completed_offset IS NULL OR :markerOffset >= snapshot_completed_offset,
                        :snapshotId,
                        last_snapshot_id
                    ),
                    snapshot_completed_offset = GREATEST(
                        COALESCE(snapshot_completed_offset, -1),
                        :markerOffset
                    ),
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE consumer_group = :consumerGroup AND topic_name = :topic
                  AND partition_id = :partition AND BINARY topic_id = BINARY :topicId
                """)
                .param("consumerGroup", consumerGroup)
                .param("topic", topic)
                .param("partition", partition)
                .param("markerOffset", markerOffset)
                .param("topicId", topicId)
                .param("snapshotId", snapshotId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Kafka projection snapshot generation mismatch");
        }
    }

    public void quarantine(String consumerGroup, ConsumerRecord<String, String> record, String reason) {
        JdbcClient.StatementSpec spec = jdbcClient.sql("""
                INSERT INTO tb_kafka_projection_quarantine
                    (consumer_group, topic_name, partition_id, record_offset, record_key, payload, reason)
                VALUES (:consumerGroup, :topic, :partition, :recordOffset, :recordKey, :payload, :reason)
                ON DUPLICATE KEY UPDATE
                    record_key = VALUES(record_key), payload = VALUES(payload), reason = VALUES(reason),
                    updated_at = CURRENT_TIMESTAMP(6)
                """)
                .param("consumerGroup", consumerGroup)
                .param("topic", record.topic())
                .param("partition", record.partition())
                .param("recordOffset", record.offset())
                .param("reason", reason.length() > 1_000 ? reason.substring(0, 1_000) : reason);
        spec = record.key() == null
                ? spec.param("recordKey", null, Types.VARCHAR)
                : spec.param("recordKey", truncate(record.key(), 512));
        spec = record.value() == null
                ? spec.param("payload", null, Types.LONGVARCHAR)
                : spec.param("payload", record.value());
        spec.update();
    }

    public Map<Integer, ProjectionCheckpoint> findByTopic(String consumerGroup, String topic) {
        return jdbcClient.sql("""
                SELECT partition_id, topic_id, next_offset, invalid_checkpoint_offset,
                       snapshot_completed_offset, invalid_record_offset
                FROM tb_kafka_projection_offsets
                WHERE consumer_group = :consumerGroup AND topic_name = :topic
                """)
                .param("consumerGroup", consumerGroup)
                .param("topic", topic)
                .query((row, rowNum) -> Map.entry(row.getObject("partition_id", Integer.class),
                        new ProjectionCheckpoint(row.getObject("next_offset", Long.class),
                                row.getObject("topic_id", String.class),
                                row.getObject("invalid_checkpoint_offset", Long.class),
                                row.getObject("snapshot_completed_offset", Long.class),
                                row.getObject("invalid_record_offset", Long.class))))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public Map<Integer, ProjectionBarrier> findBarriers(String consumerGroup, String topic) {
        return jdbcClient.sql("""
                SELECT partition_id, topic_id, target_offset
                FROM tb_kafka_projection_barriers
                WHERE consumer_group = :consumerGroup AND topic_name = :topic
                """)
                .param("consumerGroup", consumerGroup)
                .param("topic", topic)
                .query((row, rowNum) -> Map.entry(row.getObject("partition_id", Integer.class),
                        new ProjectionBarrier(row.getObject("topic_id", String.class),
                                row.getObject("target_offset", Long.class))))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private void upsertBarrier(String consumerGroup, String topic, String topicId, ProjectionPartitionRange range) {
        jdbcClient.sql("""
                INSERT INTO tb_kafka_projection_barriers
                    (consumer_group, topic_name, partition_id, topic_id, target_offset)
                VALUES (:consumerGroup, :topic, :partition, :topicId, :targetOffset)
                ON DUPLICATE KEY UPDATE
                    target_offset = IF(BINARY topic_id = BINARY VALUES(topic_id),
                        GREATEST(target_offset, VALUES(target_offset)), VALUES(target_offset)),
                    topic_id = VALUES(topic_id), captured_at = CURRENT_TIMESTAMP(6)
                """)
                .param("consumerGroup", consumerGroup)
                .param("topic", topic)
                .param("partition", range.partition())
                .param("topicId", topicId)
                .param("targetOffset", range.endOffset())
                .update();
    }

    private void ensureCheckpoint(String consumerGroup, String topic, int partition, ProjectionSeekDecision decision) {
        JdbcClient.StatementSpec spec = jdbcClient.sql(ENSURE_CHECKPOINT_SQL)
                .param("consumerGroup", consumerGroup)
                .param("topic", topic)
                .param("partition", partition)
                .param("topicId", decision.topicId())
                .param("nextOffset", decision.seekOffset());
        spec = decision.invalidCheckpointOffset() == null
                ? spec.param("invalidOffset", null, Types.BIGINT)
                : spec.param("invalidOffset", decision.invalidCheckpointOffset());
        spec.update();
    }

    private static String truncate(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length);
    }
}
