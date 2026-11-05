package com.cowork.roadmap.global.team;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Uses shared relational checkpoints and a broker topic UUID so every replica
 * evaluates the same durable projection state. A source snapshot completion
 * marker is required for every partition, including initially empty topics.
 * <p>
 * Readiness is evaluated on a schedule and cached, so request-path access checks never query the broker.
 * The scheduled refresh runs outside request transactions, so its checkpoint reads see the latest commit
 * rather than a caller's snapshot.
 */
@Component
public class TeamMemberProjectionReadiness implements ConsumerAwareRebalanceListener, HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(TeamMemberProjectionReadiness.class);

    private final ProjectionCheckpointRepository checkpoints;
    private final ProjectionTopicIdentityProvider topicIdentity;
    private final ProjectionTopicGenerationRegistry topicGeneration;
    private final String consumerGroup;
    private final String topic;

    private final Object stateLock = new Object();

    private volatile boolean initialized;
    private volatile boolean ready;
    // Guarded by stateLock. Bumped on every rebalance so a refresh that started before it cannot publish a stale result.
    private long generation;

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
        invalidate();
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
                .initializeAssignment(consumerGroup, topic, topicId, ranges, assignedPartitions);
        partitions.stream()
                .filter(partition -> topic.equals(partition.topic()))
                .forEach(partition -> consumer.seek(partition, requireSeekOffset(seekOffsets, partition.partition())));
        topicGeneration.markAssigned(topicId);
        initialized = true;
        refresh();
    }

    @Override
    public void onPartitionsRevokedBeforeCommit(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        invalidate();
        topicGeneration.markRevoked();
    }

    /** Returns the cached readiness without touching the database or the broker. */
    public boolean isReady() {
        return initialized && ready;
    }

    @Scheduled(fixedDelayString = "${projection.readiness.poll-interval-ms:1000}")
    public boolean refresh() {
        long observedGeneration;
        synchronized (stateLock) {
            observedGeneration = generation;
        }
        boolean evaluated = evaluate();
        synchronized (stateLock) {
            if (observedGeneration == generation) {
                ready = evaluated;
            }
            return isReady();
        }
    }

    private boolean evaluate() {
        if (!initialized) {
            return false;
        }
        try {
            Map<Integer, ProjectionBarrier> barriers = checkpoints.findBarriers(consumerGroup, topic);
            Map<Integer, ProjectionCheckpoint> stored = checkpoints.findByTopic(consumerGroup, topic);
            // Observe the broker last so an append concurrent with the durable reads cannot
            // leave readiness open against an older high-watermark.
            ProjectionTopicState broker = topicIdentity.topicState(topic);
            return ProjectionCatchUpPolicy
                    .isReady(broker.ranges().keySet(), broker.topicId(), barriers, stored, broker.ranges());
        } catch (RuntimeException exception) {
            log.warn("Failed to evaluate team member projection readiness", exception);
            return false;
        }
    }

    private void invalidate() {
        synchronized (stateLock) {
            generation++;
            initialized = false;
            ready = false;
        }
    }

    @Override
    public Health health() {
        return refresh() ? Health.up().build() : Health.outOfService().withDetail("initialized", initialized).build();
    }

    private long requireSeekOffset(Map<Integer, Long> seekOffsets, int partition) {
        Long offset = seekOffsets.get(partition);
        if (offset == null) {
            throw new IllegalStateException("Kafka projection seek offset is missing: " + topic + "-" + partition);
        }
        return offset;
    }
}
