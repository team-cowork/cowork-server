package com.cowork.roadmap.global.team.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.global.team.event.RolePolicyState;
import com.cowork.roadmap.global.team.event.TeamAccessState;
import com.cowork.roadmap.global.team.event.TeamMemberEvent;
import com.cowork.roadmap.global.team.projection.readiness.ProjectionReadiness;
import com.cowork.roadmap.global.team.repository.ProjectionCheckpointRepository;
import com.cowork.roadmap.global.team.repository.RoadmapPolicyRepository;

@Component
public class ProjectionRecordProcessor {

    private final TeamMemberProjectionHandler teamMemberProjectionHandler;
    private final ProjectionCheckpointRepository projectionCheckpointRepository;
    private final RoadmapPolicyRepository roadmapPolicyRepository;
    private final Map<String, ProjectionReadiness> streams;

    public ProjectionRecordProcessor(TeamMemberProjectionHandler teamMemberProjectionHandler,
            RoadmapPolicyRepository roadmapPolicyRepository,
            ProjectionCheckpointRepository projectionCheckpointRepository,
            List<ProjectionReadiness> streams) {
        this.teamMemberProjectionHandler = teamMemberProjectionHandler;
        this.roadmapPolicyRepository = roadmapPolicyRepository;
        this.projectionCheckpointRepository = projectionCheckpointRepository;
        this.streams = streams.stream()
                .collect(Collectors.toUnmodifiableMap(ProjectionReadiness::topic, Function.identity()));
    }

    @Transactional
    public void applyPolicy(ConsumerRecord<String, String> record, RolePolicyState policy) {
        roadmapPolicyRepository.apply(record.key(), policy);
        advance(record);
    }

    @Transactional
    public void applyTeamAccess(ConsumerRecord<String, String> record, TeamAccessState state) {
        roadmapPolicyRepository.applyTeamAccess(state);
        advance(record);
    }

    @Transactional
    public void apply(ConsumerRecord<String, String> record, TeamMemberEvent event) {
        teamMemberProjectionHandler.apply(event);
        advance(record);
    }

    @Transactional
    public void discard(ConsumerRecord<String, String> record, String reason) {
        ProjectionReadiness stream = streams.get(record.topic());
        String topicId = stream.topicId();
        projectionCheckpointRepository.quarantine(stream.group(), record, reason);
        projectionCheckpointRepository
                .markInvalidRecord(stream.group(), record.topic(), record.partition(), record.offset(), topicId);
        projectionCheckpointRepository
                .advance(stream.group(), record.topic(), record.partition(), record.offset() + 1, topicId);
    }

    @Transactional
    public void completeSnapshot(ConsumerRecord<String, String> record, String snapshotId) {
        ProjectionReadiness stream = streams.get(record.topic());
        String topicId = stream.topicId();
        projectionCheckpointRepository.markSnapshotCompleted(stream
                .group(), record.topic(), record.partition(), record.offset(), topicId, snapshotId);
        projectionCheckpointRepository
                .advance(stream.group(), record.topic(), record.partition(), record.offset() + 1, topicId);
    }

    private void advance(ConsumerRecord<String, String> record) {
        ProjectionReadiness stream = streams.get(record.topic());
        projectionCheckpointRepository
                .advance(stream.group(), record.topic(), record.partition(), record.offset() + 1, stream.topicId());
    }
}
