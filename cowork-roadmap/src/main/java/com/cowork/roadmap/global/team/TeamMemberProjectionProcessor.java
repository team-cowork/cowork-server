package com.cowork.roadmap.global.team;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Mono;

@Component
public class TeamMemberProjectionProcessor {

    private final TeamMemberProjectionHandler handler;
    private final ProjectionCheckpointRepository checkpoints;
    private final ProjectionTopicGenerationRegistry topicGeneration;
    private final String consumerGroup;

    public TeamMemberProjectionProcessor(TeamMemberProjectionHandler handler,
            ProjectionCheckpointRepository checkpoints,
            ProjectionTopicGenerationRegistry topicGeneration,
            @Value("${KAFKA_GROUP_ID_TEAM_MEMBER:cowork-roadmap.team-member}") String consumerGroup) {
        this.handler = handler;
        this.checkpoints = checkpoints;
        this.topicGeneration = topicGeneration;
        this.consumerGroup = consumerGroup;
    }

    @Transactional
    public Mono<Void> apply(ConsumerRecord<String, String> record, TeamMemberEvent event) {
        return handler.apply(event).then(advance(record));
    }

    @Transactional
    public Mono<Void> discard(ConsumerRecord<String, String> record, String reason) {
        String topicId = topicGeneration.requireTopicId();
        return checkpoints.quarantine(consumerGroup, record, reason)
                .then(checkpoints
                        .markInvalidRecord(consumerGroup, record.topic(), record.partition(), record.offset(), topicId))
                .then(checkpoints
                        .advance(consumerGroup, record.topic(), record.partition(), record.offset() + 1, topicId));
    }

    @Transactional
    public Mono<Void> completeSnapshot(ConsumerRecord<String, String> record, String snapshotId) {
        String topicId = topicGeneration.requireTopicId();
        return checkpoints
                .markSnapshotCompleted(consumerGroup,
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        topicId,
                        snapshotId)
                .then(checkpoints
                        .advance(consumerGroup, record.topic(), record.partition(), record.offset() + 1, topicId));
    }

    private Mono<Void> advance(ConsumerRecord<String, String> record) {
        return checkpoints.advance(consumerGroup,
                record.topic(),
                record.partition(),
                record.offset() + 1,
                topicGeneration.requireTopicId());
    }
}
