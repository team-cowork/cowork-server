package com.cowork.roadmap.global.team.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.global.team.event.RolePolicyState;
import com.cowork.roadmap.global.team.event.TeamMemberEvent;
import com.cowork.roadmap.global.team.projection.readiness.ProjectionReadiness;
import com.cowork.roadmap.global.team.repository.ProjectionCheckpointRepository;
import com.cowork.roadmap.global.team.repository.RoadmapPolicyRepository;

@Component
public class ProjectionRecordProcessor {

    private final TeamMemberProjectionHandler handler;
    private final ProjectionCheckpointRepository checkpoints;
    private final RoadmapPolicyRepository policies;
    private final Map<String, ProjectionReadiness> streams;

    public ProjectionRecordProcessor(TeamMemberProjectionHandler handler,
            RoadmapPolicyRepository policies,
            ProjectionCheckpointRepository checkpoints,
            List<ProjectionReadiness> streams) {
        this.handler = handler;
        this.policies = policies;
        this.checkpoints = checkpoints;
        this.streams = streams.stream()
                .collect(Collectors.toUnmodifiableMap(ProjectionReadiness::topic, Function.identity()));
    }

    @Transactional
    public void applyPolicy(ConsumerRecord<String, String> record, RolePolicyState policy) {
        policies.apply(record.key(), policy);
        advance(record);
    }

    @Transactional
    public void apply(ConsumerRecord<String, String> record, TeamMemberEvent event) {
        handler.apply(event);
        advance(record);
    }

    @Transactional
    public void discard(ConsumerRecord<String, String> record, String reason) {
        ProjectionReadiness stream = streams.get(record.topic());
        String topicId = stream.topicId();
        checkpoints.quarantine(stream.group(), record, reason);
        checkpoints.markInvalidRecord(stream.group(), record.topic(), record.partition(), record.offset(), topicId);
        checkpoints.advance(stream.group(), record.topic(), record.partition(), record.offset() + 1, topicId);
    }

    @Transactional
    public void completeSnapshot(ConsumerRecord<String, String> record, String snapshotId) {
        ProjectionReadiness stream = streams.get(record.topic());
        String topicId = stream.topicId();
        checkpoints.markSnapshotCompleted(stream
                .group(), record.topic(), record.partition(), record.offset(), topicId, snapshotId);
        checkpoints.advance(stream.group(), record.topic(), record.partition(), record.offset() + 1, topicId);
    }

    private void advance(ConsumerRecord<String, String> record) {
        ProjectionReadiness stream = streams.get(record.topic());
        checkpoints.advance(stream.group(), record.topic(), record.partition(), record.offset() + 1, stream.topicId());
    }
}
