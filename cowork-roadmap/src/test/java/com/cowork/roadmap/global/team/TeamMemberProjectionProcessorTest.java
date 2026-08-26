package com.cowork.roadmap.global.team;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class TeamMemberProjectionProcessorTest {

    private final TeamMemberProjectionHandler handler = mock(TeamMemberProjectionHandler.class);
    private final ProjectionCheckpointRepository checkpoints = mock(ProjectionCheckpointRepository.class);
    private final ProjectionTopicGenerationRegistry topicGeneration = new ProjectionTopicGenerationRegistry();
    private final TeamMemberProjectionProcessor processor = new TeamMemberProjectionProcessor(handler,
            checkpoints,
            topicGeneration,
            "cowork-roadmap.team-member");

    TeamMemberProjectionProcessorTest() {
        topicGeneration.markAssigned("topic-id");
    }

    @Test
    void advancesCheckpointOnlyAfterProjectionApplyCompletes() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("team.member.event", 2, 7L, "11:22", "{}");
        TeamMemberEvent event = new TeamMemberEvent("UPSERT", 11L, 22L, "MEMBER", "platform", Instant.now());
        when(handler.apply(event)).thenReturn(Mono.empty());
        when(checkpoints.advance("cowork-roadmap.team-member", "team.member.event", 2, 8L, "topic-id"))
                .thenReturn(Mono.empty());

        StepVerifier.create(processor.apply(record, event)).verifyComplete();

        InOrder order = inOrder(handler, checkpoints);
        order.verify(handler).apply(event);
        order.verify(checkpoints).advance("cowork-roadmap.team-member", "team.member.event", 2, 8L, "topic-id");
    }

    @Test
    void snapshotMarkerAndNextOffsetArePersistedInOrder() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("team.member.event",
                2,
                7L,
                ProjectionSnapshotCompletion.KEY_PREFIX + "2",
                "{}");
        when(checkpoints.markSnapshotCompleted("cowork-roadmap.team-member", "team.member.event", 2, 7L, "topic-id"))
                .thenReturn(Mono.empty());
        when(checkpoints.advance("cowork-roadmap.team-member", "team.member.event", 2, 8L, "topic-id"))
                .thenReturn(Mono.empty());

        StepVerifier.create(processor.completeSnapshot(record)).verifyComplete();

        InOrder order = inOrder(checkpoints);
        order.verify(checkpoints)
                .markSnapshotCompleted("cowork-roadmap.team-member", "team.member.event", 2, 7L, "topic-id");
        order.verify(checkpoints).advance("cowork-roadmap.team-member", "team.member.event", 2, 8L, "topic-id");
    }
}
