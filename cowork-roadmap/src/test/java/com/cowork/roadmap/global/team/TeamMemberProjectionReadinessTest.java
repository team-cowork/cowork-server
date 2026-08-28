package com.cowork.roadmap.global.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import reactor.core.publisher.Mono;

class TeamMemberProjectionReadinessTest {

    private static final String TOPIC = "team.member.event";
    private static final String CONSUMER_GROUP = "cowork-roadmap.team-member";
    private static final String TOPIC_ID = "topic-id";

    private final ProjectionCheckpointRepository checkpoints = mock(ProjectionCheckpointRepository.class);
    private final ProjectionTopicIdentityProvider topicIdentity = mock(ProjectionTopicIdentityProvider.class);
    private final TeamMemberProjectionReadiness readiness = new TeamMemberProjectionReadiness(checkpoints,
            topicIdentity,
            new ProjectionTopicGenerationRegistry(),
            CONSUMER_GROUP,
            TOPIC);

    @Test
    void newEmptyTopicRemainsUnavailableUntilSnapshotCompletionMarkerIsConsumed() {
        readiness.markInitializedForPolicyTest();
        brokerState(TOPIC_ID, Map.of(0, new ProjectionBrokerRange(0L, 0L)));
        when(checkpoints.findBarriers(CONSUMER_GROUP, TOPIC))
                .thenReturn(Mono.just(Map.of(0, new ProjectionBarrier(TOPIC_ID, 0L))));
        when(checkpoints.findByTopic(CONSUMER_GROUP, TOPIC))
                .thenReturn(Mono.just(Map.of(0, new ProjectionCheckpoint(0L, TOPIC_ID, null, null))));

        assertThat(readiness.isReady()).isFalse();
        assertThat(readiness.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    }

    @Test
    void allPartitionsRequireACompletionMarkerAndStartupBarrier() {
        readiness.markInitializedForPolicyTest();
        brokerState(TOPIC_ID, Map.of(0, new ProjectionBrokerRange(0L, 2L), 1, new ProjectionBrokerRange(0L, 2L)));
        when(checkpoints.findBarriers(CONSUMER_GROUP, TOPIC)).thenReturn(
                Mono.just(Map.of(0, new ProjectionBarrier(TOPIC_ID, 1L), 1, new ProjectionBarrier(TOPIC_ID, 1L))));
        when(checkpoints.findByTopic(CONSUMER_GROUP, TOPIC)).thenReturn(Mono.just(Map.of(0,
                new ProjectionCheckpoint(2L, TOPIC_ID, null, 1L),
                1,
                new ProjectionCheckpoint(2L, TOPIC_ID, null, null))));

        assertThat(readiness.isReady()).isFalse();
    }

    @Test
    void replicaWithoutLocalAssignmentUsesSharedDatabaseState() {
        readiness.markInitializedForPolicyTest();
        brokerState(TOPIC_ID, Map.of(0, new ProjectionBrokerRange(0L, 1L)));
        when(checkpoints.findBarriers(CONSUMER_GROUP, TOPIC))
                .thenReturn(Mono.just(Map.of(0, new ProjectionBarrier(TOPIC_ID, 0L))));
        when(checkpoints.findByTopic(CONSUMER_GROUP, TOPIC))
                .thenReturn(Mono.just(Map.of(0, new ProjectionCheckpoint(1L, TOPIC_ID, null, 0L))));

        assertThat(readiness.isReady()).isTrue();
        assertThat(readiness.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void topicRecreationFailsClosedEvenWhenNumericOffsetsOverlap() {
        readiness.markInitializedForPolicyTest();
        brokerState("new-topic-id", Map.of(0, new ProjectionBrokerRange(0L, 10L)));
        when(checkpoints.findBarriers(CONSUMER_GROUP, TOPIC))
                .thenReturn(Mono.just(Map.of(0, new ProjectionBarrier(TOPIC_ID, 5L))));
        when(checkpoints.findByTopic(CONSUMER_GROUP, TOPIC))
                .thenReturn(Mono.just(Map.of(0, new ProjectionCheckpoint(5L, TOPIC_ID, null, 4L))));

        assertThat(readiness.isReady()).isFalse();
    }

    @Test
    void retentionGapAndCheckpointBeyondEndFailClosed() {
        Map<Integer, ProjectionBarrier> barriers = Map.of(0, new ProjectionBarrier(TOPIC_ID, 5L));

        assertThat(ProjectionCatchUpPolicy.isReady(Set.of(0),
                TOPIC_ID,
                barriers,
                Map.of(0, new ProjectionCheckpoint(4L, TOPIC_ID, null, 3L)),
                Map.of(0, new ProjectionBrokerRange(5L, 10L)))).isFalse();
        assertThat(ProjectionCatchUpPolicy.isReady(Set.of(0),
                TOPIC_ID,
                barriers,
                Map.of(0, new ProjectionCheckpoint(11L, TOPIC_ID, null, 3L)),
                Map.of(0, new ProjectionBrokerRange(0L, 10L)))).isFalse();
    }

    @Test
    void initializationCallbackIsRequiredBeforeSharedStateCanBecomeReady() {
        assertThat(readiness.isReady()).isFalse();
    }

    private void brokerState(String topicId, Map<Integer, ProjectionBrokerRange> ranges) {
        when(topicIdentity.topicState(TOPIC)).thenReturn(new ProjectionTopicState(topicId, ranges));
    }
}
