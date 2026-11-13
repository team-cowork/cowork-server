package com.cowork.roadmap.global.team;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.WakeupException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Uses shared relational checkpoints and a broker topic UUID so every replica
 * evaluates the same durable projection state. A source snapshot completion
 * marker is required for every partition, including initially empty topics.
 * <p>
 * Readiness is evaluated on a schedule and cached, so request-path access
 * checks never query the broker. The scheduled refresh runs outside request
 * transactions, so its checkpoint reads see the latest commit rather than a
 * caller's snapshot.
 */
@Slf4j
@Component
public class TeamMemberProjectionReadiness implements ConsumerAwareRebalanceListener, HealthIndicator {

    private final ProjectionCheckpointRepository checkpoints;
    private final ProjectionTopicIdentityProvider topicIdentity;
    private final ProjectionTopicGenerationRegistry topicGeneration;
    private final String consumerGroup;
    private final String topic;
    private final long assignmentRetryBackoffMs;
    private final long assignmentRetryTimeoutMs;

    private final Object stateLock = new Object();

    private volatile boolean initialized;
    private volatile boolean ready;
    // Guarded by stateLock. Bumped on every rebalance so a refresh that started
    // before it cannot publish a stale result.
    private long generation;
    // Only accessed on the consumer thread. Limits stack traces to the first
    // failure of a run.
    private int consecutiveAssignmentFailures;

    public TeamMemberProjectionReadiness(ProjectionCheckpointRepository checkpoints,
            ProjectionTopicIdentityProvider topicIdentity,
            ProjectionTopicGenerationRegistry topicGeneration,
            @Value("${KAFKA_GROUP_ID_TEAM_MEMBER:cowork-roadmap.team-member}") String consumerGroup,
            @Value("${KAFKA_TOPIC_TEAM_MEMBER:team.member.event}") String topic,
            @Value("${projection.readiness.assignment-retry-backoff-ms:1000}") long assignmentRetryBackoffMs,
            @Value("${projection.readiness.assignment-retry-timeout-ms:60000}") long assignmentRetryTimeoutMs) {
        this.checkpoints = checkpoints;
        this.topicIdentity = topicIdentity;
        this.topicGeneration = topicGeneration;
        this.consumerGroup = consumerGroup;
        this.topic = topic;
        this.assignmentRetryBackoffMs = Math.max(0L, assignmentRetryBackoffMs);
        this.assignmentRetryTimeoutMs = Math.max(0L, assignmentRetryTimeoutMs);
    }

    @Override
    public void onPartitionsAssigned(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        invalidate();
        // Retry inside the callback first: a rebalance would also take every healthy
        // member out of readiness. Keep the timeout well below max.poll.interval.ms,
        // because one attempt can itself block for the client API timeout.
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(assignmentRetryTimeoutMs);
        while (true) {
            try {
                initializeAssignment(consumer, partitions);
                consecutiveAssignmentFailures = 0;
                return;
            } catch (WakeupException | InterruptException exception) {
                // The coordinator runs the callback again on the next poll.
                throw exception;
            } catch (RuntimeException exception) {
                logAssignmentFailure(exception);
            }
            if (System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(assignmentRetryBackoffMs) - deadline >= 0) {
                requestRebalance(consumer);
                return;
            }
            try {
                Thread.sleep(assignmentRetryBackoffMs);
            } catch (InterruptedException exception) {
                // The consumer is stopping, so a new rebalance is not useful.
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void initializeAssignment(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
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

    private void logAssignmentFailure(RuntimeException exception) {
        consecutiveAssignmentFailures++;
        if (consecutiveAssignmentFailures == 1) {
            log.warn("Failed to initialize team member projection assignment", exception);
        } else {
            log.warn("Failed to initialize team member projection assignment again, failure {}: {}",
                    consecutiveAssignmentFailures,
                    exception.toString());
        }
    }

    private void requestRebalance(Consumer<?, ?> consumer) {
        log.warn("Requesting a rebalance to retry team member projection assignment");
        // The container only logs listener failures, so without a new rebalance the
        // assignment would stay uninitialized until restart. This depends on the
        // classic
        // group protocol: with group.protocol=consumer, enforceRebalance only logs a
        // warning.
        consumer.enforceRebalance("team member projection assignment initialization failed");
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
        return isReady() ? Health.up().build() : Health.outOfService().withDetail("initialized", initialized).build();
    }

    private long requireSeekOffset(Map<Integer, Long> seekOffsets, int partition) {
        Long offset = seekOffsets.get(partition);
        if (offset == null) {
            throw new IllegalStateException("Kafka projection seek offset is missing: " + topic + "-" + partition);
        }
        return offset;
    }
}
