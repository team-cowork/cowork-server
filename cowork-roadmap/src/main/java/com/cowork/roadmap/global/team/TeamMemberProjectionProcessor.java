package com.cowork.roadmap.global.team;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
    public void apply(ConsumerRecord<String, String> record, TeamMemberEvent event) {
        handler.apply(event);
        advance(record);
    }

    @Transactional
    public void discard(ConsumerRecord<String, String> record, String reason) {
        String topicId = topicGeneration.requireTopicId();
        checkpoints.quarantine(consumerGroup, record, reason);
        checkpoints.markInvalidRecord(consumerGroup, record.topic(), record.partition(), record.offset(), topicId);
        checkpoints.advance(consumerGroup, record.topic(), record.partition(), record.offset() + 1, topicId);
    }

    @Transactional
    public void completeSnapshot(ConsumerRecord<String, String> record, String snapshotId) {
        String topicId = topicGeneration.requireTopicId();
        checkpoints.markSnapshotCompleted(consumerGroup,
                record.topic(),
                record.partition(),
                record.offset(),
                topicId,
                snapshotId);
        checkpoints.advance(consumerGroup, record.topic(), record.partition(), record.offset() + 1, topicId);
    }

    private void advance(ConsumerRecord<String, String> record) {
        checkpoints.advance(consumerGroup,
                record.topic(),
                record.partition(),
                record.offset() + 1,
                topicGeneration.requireTopicId());
    }
}
