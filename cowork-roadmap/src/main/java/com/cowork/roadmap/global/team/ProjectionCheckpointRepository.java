package com.cowork.roadmap.global.team;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class ProjectionCheckpointRepository {

    private static final int QUERY_TIMEOUT_MS = 10_000;

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

    private final EntityManager entityManager;

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
        int updated = entityManager.createNativeQuery("""
                UPDATE tb_kafka_projection_offsets
                SET next_offset = GREATEST(next_offset, :nextOffset),
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE consumer_group = :consumerGroup AND topic_name = :topic
                  AND partition_id = :partition AND BINARY topic_id = BINARY :topicId
                """)
                .setHint("jakarta.persistence.query.timeout", QUERY_TIMEOUT_MS)
                .setParameter("consumerGroup", consumerGroup)
                .setParameter("topic", topic)
                .setParameter("partition", partition)
                .setParameter("nextOffset", nextOffset)
                .setParameter("topicId", topicId)
                .executeUpdate();
        if (updated != 1) {
            throw new IllegalStateException("Kafka projection checkpoint generation mismatch");
        }
    }

    public void markInvalidRecord(String consumerGroup,
            String topic,
            int partition,
            long recordOffset,
            String topicId) {
        int updated = entityManager.createNativeQuery("""
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
                .setHint("jakarta.persistence.query.timeout", QUERY_TIMEOUT_MS)
                .setParameter("consumerGroup", consumerGroup)
                .setParameter("topic", topic)
                .setParameter("partition", partition)
                .setParameter("recordOffset", recordOffset)
                .setParameter("topicId", topicId)
                .executeUpdate();
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
        int updated = entityManager.createNativeQuery("""
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
                .setHint("jakarta.persistence.query.timeout", QUERY_TIMEOUT_MS)
                .setParameter("consumerGroup", consumerGroup)
                .setParameter("topic", topic)
                .setParameter("partition", partition)
                .setParameter("markerOffset", markerOffset)
                .setParameter("topicId", topicId)
                .setParameter("snapshotId", snapshotId)
                .executeUpdate();
        if (updated != 1) {
            throw new IllegalStateException("Kafka projection snapshot generation mismatch");
        }
    }

    public void quarantine(String consumerGroup, ConsumerRecord<String, String> record, String reason) {
        entityManager.createNativeQuery("""
                INSERT INTO tb_kafka_projection_quarantine
                    (consumer_group, topic_name, partition_id, record_offset, record_key, payload, reason)
                VALUES (:consumerGroup, :topic, :partition, :recordOffset, :recordKey, :payload, :reason)
                ON DUPLICATE KEY UPDATE
                    record_key = VALUES(record_key), payload = VALUES(payload), reason = VALUES(reason),
                    updated_at = CURRENT_TIMESTAMP(6)
                """)
                .setHint("jakarta.persistence.query.timeout", QUERY_TIMEOUT_MS)
                .setParameter("consumerGroup", consumerGroup)
                .setParameter("topic", record.topic())
                .setParameter("partition", record.partition())
                .setParameter("recordOffset", record.offset())
                .setParameter("reason", reason.length() > 1_000 ? reason.substring(0, 1_000) : reason)
                .setParameter("recordKey", record.key() == null ? null : truncate(record.key(), 512))
                .setParameter("payload", record.value())
                .executeUpdate();
    }

    public Map<Integer, ProjectionCheckpoint> findByTopic(String consumerGroup, String topic) {
        List<?> results = entityManager.createNativeQuery("""
                SELECT partition_id, topic_id, next_offset, invalid_checkpoint_offset,
                       snapshot_completed_offset, invalid_record_offset
                FROM tb_kafka_projection_offsets
                WHERE consumer_group = :consumerGroup AND topic_name = :topic
                """, Tuple.class)
                .setHint("jakarta.persistence.query.timeout", QUERY_TIMEOUT_MS)
                .setParameter("consumerGroup", consumerGroup)
                .setParameter("topic", topic)
                .getResultList();
        Map<Integer, ProjectionCheckpoint> checkpoints = new HashMap<>();
        for (Object result : results) {
            Tuple row = (Tuple) result;
            checkpoints.put(row.get("partition_id", Number.class).intValue(),
                    new ProjectionCheckpoint(row.get("next_offset", Number.class).longValue(),
                            row.get("topic_id", String.class),
                            nullableOffset(row, "invalid_checkpoint_offset"),
                            nullableOffset(row, "snapshot_completed_offset"),
                            nullableOffset(row, "invalid_record_offset")));
        }
        return checkpoints;
    }

    public Map<Integer, ProjectionBarrier> findBarriers(String consumerGroup, String topic) {
        List<?> results = entityManager.createNativeQuery("""
                SELECT partition_id, topic_id, target_offset
                FROM tb_kafka_projection_barriers
                WHERE consumer_group = :consumerGroup AND topic_name = :topic
                """, Tuple.class)
                .setHint("jakarta.persistence.query.timeout", QUERY_TIMEOUT_MS)
                .setParameter("consumerGroup", consumerGroup)
                .setParameter("topic", topic)
                .getResultList();
        Map<Integer, ProjectionBarrier> barriers = new HashMap<>();
        for (Object result : results) {
            Tuple row = (Tuple) result;
            barriers.put(row.get("partition_id", Number.class).intValue(),
                    new ProjectionBarrier(row.get("topic_id", String.class),
                            row.get("target_offset", Number.class).longValue()));
        }
        return barriers;
    }

    private void upsertBarrier(String consumerGroup, String topic, String topicId, ProjectionPartitionRange range) {
        entityManager.createNativeQuery("""
                INSERT INTO tb_kafka_projection_barriers
                    (consumer_group, topic_name, partition_id, topic_id, target_offset)
                VALUES (:consumerGroup, :topic, :partition, :topicId, :targetOffset)
                ON DUPLICATE KEY UPDATE
                    target_offset = IF(BINARY topic_id = BINARY VALUES(topic_id),
                        GREATEST(target_offset, VALUES(target_offset)), VALUES(target_offset)),
                    topic_id = VALUES(topic_id), captured_at = CURRENT_TIMESTAMP(6)
                """)
                .setHint("jakarta.persistence.query.timeout", QUERY_TIMEOUT_MS)
                .setParameter("consumerGroup", consumerGroup)
                .setParameter("topic", topic)
                .setParameter("partition", range.partition())
                .setParameter("topicId", topicId)
                .setParameter("targetOffset", range.endOffset())
                .executeUpdate();
    }

    private void ensureCheckpoint(String consumerGroup, String topic, int partition, ProjectionSeekDecision decision) {
        entityManager.createNativeQuery(ENSURE_CHECKPOINT_SQL)
                .setHint("jakarta.persistence.query.timeout", QUERY_TIMEOUT_MS)
                .setParameter("consumerGroup", consumerGroup)
                .setParameter("topic", topic)
                .setParameter("partition", partition)
                .setParameter("topicId", decision.topicId())
                .setParameter("nextOffset", decision.seekOffset())
                .setParameter("invalidOffset", decision.invalidCheckpointOffset())
                .executeUpdate();
    }

    private static Long nullableOffset(Tuple row, String column) {
        Number value = row.get(column, Number.class);
        return value == null ? null : value.longValue();
    }

    private static String truncate(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length);
    }
}
