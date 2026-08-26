package com.cowork.roadmap.global.team;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;
import org.springframework.stereotype.Component;

/**
 * Uses shared relational checkpoints and a broker topic UUID so every replica
 * evaluates the same durable projection state. A source snapshot completion
 * marker is required for every partition, including initially empty topics.
 */
@Component
public class TeamMemberProjectionReadiness implements ConsumerAwareRebalanceListener, HealthIndicator {

    private static final Duration CHECKPOINT_TIMEOUT = Duration.ofSeconds(10);

    private final ProjectionCheckpointRepository checkpoints;
    private final ProjectionTopicIdentityProvider topicIdentity;
    private final ProjectionTopicGenerationRegistry topicGeneration;
    private final String consumerGroup;
    private final String topic;

    private volatile boolean initialized;

    public TeamMemberProjectionReadiness(ProjectionCheckpointRepository checkpoints,
            ProjectionTopicIdentityProvider topicIdentity,
            ProjectionTopicGenerationRegistry topicGeneration,
            @Value("${KAFKA_GROUP_ID_TEAM_MEMBER:cowork-roadmap.team-member}") String consumerGroup,
            @Value("${KAFKA_TOPIC_TEAM_MEMBER:team.member.event}") String topic) {
        this.checkpoints = checkpoints;
        this.topicIdentity = topicIdentity;
        this.topicGeneration = topicGeneration;
        this.consumerGroup = consumerGroup;
        this.topic = topic;
    }

    @Override
    public void onPartitionsAssigned(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        initialized = false;
        String topicId = topicIdentity.topicId(topic);
        List<TopicPartition> allPartitions = consumer.partitionsFor(topic)
                .stream()
                .map(info -> new TopicPartition(topic, info.partition()))
                .toList();
        if (allPartitions.isEmpty()) {
            throw new IllegalStateException("Kafka topic has no partitions: " + topic);
        }
        Map<TopicPartition, Long> beginnings = consumer.beginningOffsets(allPartitions);
        Map<TopicPartition, Long> ends = consumer.endOffsets(allPartitions);
        List<ProjectionPartitionRange> ranges = allPartitions.stream()
                .map(partition -> new ProjectionPartitionRange(partition.partition(),
                        beginnings.get(partition),
                        ends.get(partition)))
                .toList();
        Set<Integer> assignedPartitions = partitions.stream()
                .filter(partition -> topic.equals(partition.topic()))
                .map(TopicPartition::partition)
                .collect(Collectors.toSet());
        Map<Integer, Long> seekOffsets = checkpoints
                .initializeAssignment(consumerGroup, topic, topicId, ranges, assignedPartitions)
                .block(CHECKPOINT_TIMEOUT);
        if (seekOffsets == null) {
            throw new IllegalStateException("Kafka projection checkpoints were not initialized");
        }
        partitions.stream()
                .filter(partition -> topic.equals(partition.topic()))
                .forEach(partition -> consumer.seek(partition, requireSeekOffset(seekOffsets, partition.partition())));
        topicGeneration.markAssigned(topicId);
        initialized = true;
    }

    @Override
    public void onPartitionsRevokedBeforeCommit(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        initialized = false;
        topicGeneration.markRevoked();
    }

    public boolean isReady() {
        if (!initialized) {
            return false;
        }
        try {
            ProjectionTopicState broker = topicIdentity.topicState(topic);
            Map<Integer, ProjectionBarrier> barriers = requireBarriers();
            Map<Integer, ProjectionCheckpoint> stored = requireCheckpoints();
            return ProjectionCatchUpPolicy
                    .isReady(broker.ranges().keySet(), broker.topicId(), barriers, stored, broker.ranges());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    @Override
    public Health health() {
        return isReady() ? Health.up().build() : Health.outOfService().withDetail("initialized", initialized).build();
    }

    void markInitializedForPolicyTest() {
        initialized = true;
    }

    private Map<Integer, ProjectionCheckpoint> requireCheckpoints() {
        Map<Integer, ProjectionCheckpoint> values = checkpoints.findByTopic(consumerGroup, topic)
                .block(CHECKPOINT_TIMEOUT);
        if (values == null) {
            throw new IllegalStateException("Kafka projection checkpoints were not loaded");
        }
        return values;
    }

    private Map<Integer, ProjectionBarrier> requireBarriers() {
        Map<Integer, ProjectionBarrier> values = checkpoints.findBarriers(consumerGroup, topic)
                .block(CHECKPOINT_TIMEOUT);
        if (values == null) {
            throw new IllegalStateException("Kafka projection barriers were not loaded");
        }
        return values;
    }

    private long requireSeekOffset(Map<Integer, Long> seekOffsets, int partition) {
        Long offset = seekOffsets.get(partition);
        if (offset == null) {
            throw new IllegalStateException("Kafka projection seek offset is missing: " + topic + "-" + partition);
        }
        return offset;
    }
}
