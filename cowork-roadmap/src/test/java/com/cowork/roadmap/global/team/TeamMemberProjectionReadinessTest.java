package com.cowork.roadmap.global.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

class TeamMemberProjectionReadinessTest {

    private static final String GROUP = "cowork-roadmap.team-member";
    private static final String TOPIC = "team.member.event";
    private static final String TOPIC_ID = "topic-id";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);

    private final ProjectionCheckpointRepository checkpoints = mock(ProjectionCheckpointRepository.class);
    private final ProjectionTopicIdentityProvider topicIdentity = mock(ProjectionTopicIdentityProvider.class);
    private final ProjectionTopicGenerationRegistry topicGeneration = new ProjectionTopicGenerationRegistry();
    @SuppressWarnings("unchecked")
    private final Consumer<String, String> consumer = mock(Consumer.class);
    private final TeamMemberProjectionReadiness readiness = new TeamMemberProjectionReadiness(checkpoints,
            topicIdentity,
            topicGeneration,
            GROUP,
            TOPIC);

    @Test
    void beforeAssignment_isNotReadyWithoutQueryingBroker() {
        assertFalse(readiness.refresh());
        assertFalse(readiness.isReady());
        verifyNoInteractions(checkpoints, topicIdentity);
    }

    @Test
    void afterAssignmentWithCaughtUpCheckpoint_isReady() {
        stubCaughtUp();

        readiness.onPartitionsAssigned(consumer, List.of(PARTITION));

        assertTrue(readiness.isReady());
    }

    @Test
    void isReady_readsCachedValueWithoutQueryingBrokerOrDatabase() {
        stubCaughtUp();
        readiness.onPartitionsAssigned(consumer, List.of(PARTITION));
        clearInvocations(checkpoints, topicIdentity);

        assertTrue(readiness.isReady());
        assertTrue(readiness.isReady());

        verifyNoInteractions(checkpoints, topicIdentity);
    }

    @Test
    void refreshFailure_switchesToNotReady() {
        stubCaughtUp();
        readiness.onPartitionsAssigned(consumer, List.of(PARTITION));
        when(topicIdentity.topicState(TOPIC)).thenThrow(new IllegalStateException("broker unavailable"));

        assertFalse(readiness.refresh());
        assertFalse(readiness.isReady());
    }

    @Test
    void refreshAfterProjectionFallsBehind_switchesToNotReady() {
        stubCaughtUp();
        readiness.onPartitionsAssigned(consumer, List.of(PARTITION));
        when(topicIdentity.topicState(TOPIC))
                .thenReturn(new ProjectionTopicState(TOPIC_ID, Map.of(0, new ProjectionBrokerRange(0L, 6L))));

        assertFalse(readiness.refresh());
        assertFalse(readiness.isReady());
    }

    @Test
    void refreshAfterRecovery_switchesBackToReady() {
        stubCaughtUp();
        readiness.onPartitionsAssigned(consumer, List.of(PARTITION));
        when(topicIdentity.topicState(TOPIC)).thenThrow(new IllegalStateException("broker unavailable"))
                .thenReturn(new ProjectionTopicState(TOPIC_ID, Map.of(0, new ProjectionBrokerRange(0L, 5L))));
        readiness.refresh();

        assertTrue(readiness.refresh());
    }

    @Test
    void revocation_switchesToNotReadyImmediately() {
        stubCaughtUp();
        readiness.onPartitionsAssigned(consumer, List.of(PARTITION));

        readiness.onPartitionsRevokedBeforeCommit(consumer, List.of(PARTITION));

        assertFalse(readiness.isReady());
        assertFalse(readiness.refresh());
    }

    @Test
    void refresh_isScheduledWithConfigurablePollIntervalDefaultingToOneSecond() throws NoSuchMethodException {
        Scheduled scheduled = TeamMemberProjectionReadiness.class.getMethod("refresh").getAnnotation(Scheduled.class);

        assertEquals("${projection.readiness.poll-interval-ms:1000}", scheduled.fixedDelayString());
    }

    private void stubCaughtUp() {
        when(topicIdentity.topicId(TOPIC)).thenReturn(TOPIC_ID);
        when(consumer.partitionsFor(TOPIC)).thenReturn(List.of(new PartitionInfo(TOPIC, 0, null, null, null)));
        when(consumer.beginningOffsets(any())).thenReturn(Map.of(PARTITION, 0L));
        when(consumer.endOffsets(any())).thenReturn(Map.of(PARTITION, 5L));
        when(checkpoints.initializeAssignment(anyString(), anyString(), anyString(), anyList(), anySet()))
                .thenReturn(Map.of(0, 5L));
        when(checkpoints.findBarriers(GROUP, TOPIC)).thenReturn(Map.of(0, new ProjectionBarrier(TOPIC_ID, 5L)));
        when(checkpoints.findByTopic(GROUP, TOPIC))
                .thenReturn(Map.of(0, new ProjectionCheckpoint(5L, TOPIC_ID, null, 3L)));
        when(topicIdentity.topicState(TOPIC))
                .thenReturn(new ProjectionTopicState(TOPIC_ID, Map.of(0, new ProjectionBrokerRange(0L, 5L))));
    }
}
