package com.cowork.roadmap.global.team;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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

    private final DatabaseClient databaseClient;

    @Transactional
    public Mono<Map<Integer, Long>> initializeAssignment(String consumerGroup,
            String topic,
            String topicId,
            List<ProjectionPartitionRange> ranges,
            Set<Integer> assignedPartitions) {
        return findByTopic(consumerGroup, topic).flatMapMany(existing -> Flux.fromIterable(ranges).concatMap(range -> {
            ProjectionSeekDecision decision = ProjectionCatchUpPolicy
                    .seekDecision(existing.get(range.partition()), range.beginningOffset(), range.endOffset(), topicId);
            return upsertBarrier(consumerGroup, topic, topicId, range)
                    .then(ensureCheckpoint(consumerGroup, topic, range.partition(), decision))
                    .thenReturn(Map.entry(range.partition(), decision.seekOffset()));
        }))
                .filter(entry -> assignedPartitions.contains(entry.getKey()))
                .collect(HashMap::new, (offsets, entry) -> offsets.put(entry.getKey(), entry.getValue()));
    }

    public Mono<Void> advance(String consumerGroup, String topic, int partition, long nextOffset, String topicId) {
        return databaseClient.sql("""
                UPDATE tb_kafka_projection_offsets
                SET next_offset = GREATEST(next_offset, :nextOffset),
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE consumer_group = :consumerGroup AND topic_name = :topic
                  AND partition_id = :partition AND BINARY topic_id = BINARY :topicId
                """)
                .bind("consumerGroup", consumerGroup)
                .bind("topic", topic)
                .bind("partition", partition)
                .bind("nextOffset", nextOffset)
                .bind("topicId", topicId)
                .fetch()
                .rowsUpdated()
                .flatMap(updated -> updated == 1
                        ? Mono.empty()
                        : Mono.error(new IllegalStateException("Kafka projection checkpoint generation mismatch")));
    }

    public Mono<Void> markInvalidRecord(String consumerGroup,
            String topic,
            int partition,
            long recordOffset,
            String topicId) {
        return databaseClient.sql("""
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
                .bind("consumerGroup", consumerGroup)
                .bind("topic", topic)
                .bind("partition", partition)
                .bind("recordOffset", recordOffset)
                .bind("topicId", topicId)
                .fetch()
                .rowsUpdated()
                .flatMap(updated -> updated == 1
                        ? Mono.empty()
                        : Mono.error(new IllegalStateException("Kafka projection checkpoint generation mismatch")));
    }

    public Mono<Void> markSnapshotCompleted(String consumerGroup,
            String topic,
            int partition,
            long markerOffset,
            String topicId,
            String snapshotId) {
        return databaseClient.sql("""
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
                .bind("consumerGroup", consumerGroup)
                .bind("topic", topic)
                .bind("partition", partition)
                .bind("markerOffset", markerOffset)
                .bind("topicId", topicId)
                .bind("snapshotId", snapshotId)
                .fetch()
                .rowsUpdated()
                .flatMap(updated -> updated == 1
                        ? Mono.empty()
                        : Mono.error(new IllegalStateException("Kafka projection snapshot generation mismatch")));
    }

    public Mono<Void> quarantine(String consumerGroup, ConsumerRecord<String, String> record, String reason) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                INSERT INTO tb_kafka_projection_quarantine
                    (consumer_group, topic_name, partition_id, record_offset, record_key, payload, reason)
                VALUES (:consumerGroup, :topic, :partition, :recordOffset, :recordKey, :payload, :reason)
                ON DUPLICATE KEY UPDATE
                    record_key = VALUES(record_key), payload = VALUES(payload), reason = VALUES(reason),
                    updated_at = CURRENT_TIMESTAMP(6)
                """)
                .bind("consumerGroup", consumerGroup)
                .bind("topic", record.topic())
                .bind("partition", record.partition())
                .bind("recordOffset", record.offset())
                .bind("reason", reason.length() > 1_000 ? reason.substring(0, 1_000) : reason);
        spec = record.key() == null
                ? spec.bindNull("recordKey", String.class)
                : spec.bind("recordKey", truncate(record.key(), 512));
        spec = record.value() == null ? spec.bindNull("payload", String.class) : spec.bind("payload", record.value());
        return spec.fetch().rowsUpdated().then();
    }

    public Mono<Map<Integer, ProjectionCheckpoint>> findByTopic(String consumerGroup, String topic) {
        return databaseClient.sql("""
                SELECT partition_id, topic_id, next_offset, invalid_checkpoint_offset,
                       snapshot_completed_offset, invalid_record_offset
                FROM tb_kafka_projection_offsets
                WHERE consumer_group = :consumerGroup AND topic_name = :topic
                """)
                .bind("consumerGroup", consumerGroup)
                .bind("topic", topic)
                .map((row, metadata) -> Map.entry(row.get("partition_id", Integer.class),
                        new ProjectionCheckpoint(row.get("next_offset", Long.class),
                                row.get("topic_id", String.class),
                                row.get("invalid_checkpoint_offset", Long.class),
                                row.get("snapshot_completed_offset", Long.class),
                                row.get("invalid_record_offset", Long.class))))
                .all()
                .collect(HashMap::new, (offsets, entry) -> offsets.put(entry.getKey(), entry.getValue()));
    }

    public Mono<Map<Integer, ProjectionBarrier>> findBarriers(String consumerGroup, String topic) {
        return databaseClient.sql("""
                SELECT partition_id, topic_id, target_offset
                FROM tb_kafka_projection_barriers
                WHERE consumer_group = :consumerGroup AND topic_name = :topic
                """)
                .bind("consumerGroup", consumerGroup)
                .bind("topic", topic)
                .map((row, metadata) -> Map.entry(row.get("partition_id", Integer.class),
                        new ProjectionBarrier(row.get("topic_id", String.class), row.get("target_offset", Long.class))))
                .all()
                .collect(HashMap::new, (barriers, entry) -> barriers.put(entry.getKey(), entry.getValue()));
    }

    private Mono<Void> upsertBarrier(String consumerGroup,
            String topic,
            String topicId,
            ProjectionPartitionRange range) {
        return databaseClient.sql("""
                INSERT INTO tb_kafka_projection_barriers
                    (consumer_group, topic_name, partition_id, topic_id, target_offset)
                VALUES (:consumerGroup, :topic, :partition, :topicId, :targetOffset)
                ON DUPLICATE KEY UPDATE
                    target_offset = IF(BINARY topic_id = BINARY VALUES(topic_id),
                        GREATEST(target_offset, VALUES(target_offset)), VALUES(target_offset)),
                    topic_id = VALUES(topic_id), captured_at = CURRENT_TIMESTAMP(6)
                """)
                .bind("consumerGroup", consumerGroup)
                .bind("topic", topic)
                .bind("partition", range.partition())
                .bind("topicId", topicId)
                .bind("targetOffset", range.endOffset())
                .fetch()
                .rowsUpdated()
                .then();
    }

    private Mono<Void> ensureCheckpoint(String consumerGroup,
            String topic,
            int partition,
            ProjectionSeekDecision decision) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(ENSURE_CHECKPOINT_SQL)
                .bind("consumerGroup", consumerGroup)
                .bind("topic", topic)
                .bind("partition", partition)
                .bind("topicId", decision.topicId())
                .bind("nextOffset", decision.seekOffset());
        spec = decision.invalidCheckpointOffset() == null
                ? spec.bindNull("invalidOffset", Long.class)
                : spec.bind("invalidOffset", decision.invalidCheckpointOffset());
        return spec.fetch().rowsUpdated().then();
    }

    private static String truncate(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length);
    }
}
