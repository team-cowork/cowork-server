package com.cowork.roadmap.global.team;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.Uuid;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

@Component
public class ProjectionTopicIdentityProvider {

    private static final long METADATA_TIMEOUT_SECONDS = 10L;

    private final Admin admin;

    public ProjectionTopicIdentityProvider(KafkaProperties kafkaProperties) {
        this.admin = Admin.create(kafkaProperties.buildAdminProperties());
    }

    String topicId(String topic) {
        try {
            var description = admin.describeTopics(java.util.List.of(topic))
                    .allTopicNames()
                    .get(METADATA_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .get(topic);
            if (description == null || description.topicId().equals(Uuid.ZERO_UUID)) {
                throw new IllegalStateException("Kafka topic ID is unavailable: " + topic);
            }
            return description.topicId().toString();
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Failed to load Kafka topic ID: " + topic, exception);
        }
    }

    ProjectionTopicState topicState(String topic) {
        try {
            var description = admin.describeTopics(java.util.List.of(topic))
                    .allTopicNames()
                    .get(METADATA_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .get(topic);
            if (description == null || description.topicId().equals(Uuid.ZERO_UUID)) {
                throw new IllegalStateException("Kafka topic ID is unavailable: " + topic);
            }
            Collection<TopicPartition> partitions = description.partitions()
                    .stream()
                    .map(info -> new TopicPartition(topic, info.partition()))
                    .toList();
            Map<TopicPartition, OffsetSpec> earliestRequest = new HashMap<>();
            Map<TopicPartition, OffsetSpec> latestRequest = new HashMap<>();
            partitions.forEach(partition -> {
                earliestRequest.put(partition, OffsetSpec.earliest());
                latestRequest.put(partition, OffsetSpec.latest());
            });
            var beginnings = admin.listOffsets(earliestRequest).all().get(METADATA_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            var ends = admin.listOffsets(latestRequest).all().get(METADATA_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Map<Integer, ProjectionBrokerRange> ranges = new HashMap<>();
            partitions.forEach(partition -> ranges.put(partition.partition(),
                    new ProjectionBrokerRange(beginnings.get(partition).offset(), ends.get(partition).offset())));
            return new ProjectionTopicState(description.topicId().toString(), Map.copyOf(ranges));
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Failed to load Kafka topic state: " + topic, exception);
        }
    }

    @PreDestroy
    public void close() {
        admin.close(Duration.ofSeconds(5));
    }
}
