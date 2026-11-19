package com.cowork.roadmap.global.team.service;

import static com.cowork.roadmap.global.team.consumer.TeamMemberEventConsumer.GROUP_ID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.global.team.event.TeamMemberEvent;
import com.cowork.roadmap.global.team.projection.topic.ProjectionTopicGenerationRegistry;
import com.cowork.roadmap.global.team.repository.ProjectionCheckpointRepository;

@Component
public class TeamMemberProjectionProcessor {

    private final TeamMemberProjectionHandler handler;
    private final ProjectionCheckpointRepository checkpoints;
    private final ProjectionTopicGenerationRegistry topicGeneration;

    public TeamMemberProjectionProcessor(TeamMemberProjectionHandler handler,
            ProjectionCheckpointRepository checkpoints,
            ProjectionTopicGenerationRegistry topicGeneration) {
        this.handler = handler;
        this.checkpoints = checkpoints;
        this.topicGeneration = topicGeneration;
    }

    @Transactional
    public void apply(ConsumerRecord<String, String> record, TeamMemberEvent event) {
        handler.apply(event);
        advance(record);
    }

    @Transactional
    public void discard(ConsumerRecord<String, String> record, String reason) {
        String topicId = topicGeneration.requireTopicId();
        checkpoints.quarantine(GROUP_ID, record, reason);
        checkpoints.markInvalidRecord(GROUP_ID, record.topic(), record.partition(), record.offset(), topicId);
        checkpoints.advance(GROUP_ID, record.topic(), record.partition(), record.offset() + 1, topicId);
    }

    @Transactional
    public void completeSnapshot(ConsumerRecord<String, String> record, String snapshotId) {
        String topicId = topicGeneration.requireTopicId();
        checkpoints.markSnapshotCompleted(GROUP_ID,
                record.topic(),
                record.partition(),
                record.offset(),
                topicId,
                snapshotId);
        checkpoints.advance(GROUP_ID, record.topic(), record.partition(), record.offset() + 1, topicId);
    }

    private void advance(ConsumerRecord<String, String> record) {
        checkpoints.advance(GROUP_ID,
                record.topic(),
                record.partition(),
                record.offset() + 1,
                topicGeneration.requireTopicId());
    }
}
