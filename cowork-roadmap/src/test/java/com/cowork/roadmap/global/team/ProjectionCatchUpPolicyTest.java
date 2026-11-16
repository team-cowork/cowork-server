package com.cowork.roadmap.global.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ProjectionCatchUpPolicyTest {

    private static final String TOPIC_ID = "topic-id";
    private static final String OTHER_TOPIC_ID = "other-topic-id";

    @Nested
    class SeekDecision {

        @Test
        void missingCheckpoint_seeksBeginningWithoutInvalidOffset() {
            assertEquals(new ProjectionSeekDecision(2L, TOPIC_ID, null),
                    ProjectionCatchUpPolicy.seekDecision(null, 2L, 10L, TOPIC_ID));
        }

        @Test
        void checkpointFromOtherTopic_seeksBeginningAndMarksCheckpointInvalid() {
            ProjectionCheckpoint checkpoint = new ProjectionCheckpoint(5L, OTHER_TOPIC_ID, null, 3L);

            assertEquals(new ProjectionSeekDecision(2L, TOPIC_ID, 5L),
                    ProjectionCatchUpPolicy.seekDecision(checkpoint, 2L, 10L, TOPIC_ID));
        }

        @Test
        void invalidCheckpointWithOffsetInRange_seeksCheckpointAndKeepsInvalidOffset() {
            ProjectionCheckpoint checkpoint = new ProjectionCheckpoint(5L, TOPIC_ID, 1L, 3L);

            assertEquals(new ProjectionSeekDecision(5L, TOPIC_ID, 1L),
                    ProjectionCatchUpPolicy.seekDecision(checkpoint, 2L, 10L, TOPIC_ID));
        }

        @Test
        void invalidCheckpointWithOffsetBelowRange_seeksBeginningAndKeepsInvalidOffset() {
            ProjectionCheckpoint checkpoint = new ProjectionCheckpoint(1L, TOPIC_ID, 0L, null);

            assertEquals(new ProjectionSeekDecision(2L, TOPIC_ID, 0L),
                    ProjectionCatchUpPolicy.seekDecision(checkpoint, 2L, 10L, TOPIC_ID));
        }

        @Test
        void invalidCheckpointWithOffsetAboveRange_seeksBeginningAndKeepsInvalidOffset() {
            ProjectionCheckpoint checkpoint = new ProjectionCheckpoint(11L, TOPIC_ID, 0L, 3L);

            assertEquals(new ProjectionSeekDecision(2L, TOPIC_ID, 0L),
                    ProjectionCatchUpPolicy.seekDecision(checkpoint, 2L, 10L, TOPIC_ID));
        }

        @Test
        void offsetBelowRange_seeksBeginningAndMarksCheckpointInvalid() {
            ProjectionCheckpoint checkpoint = new ProjectionCheckpoint(1L, TOPIC_ID, null, null);

            assertEquals(new ProjectionSeekDecision(2L, TOPIC_ID, 1L),
                    ProjectionCatchUpPolicy.seekDecision(checkpoint, 2L, 10L, TOPIC_ID));
        }

        @Test
        void offsetAboveRange_seeksBeginningAndMarksCheckpointInvalid() {
            ProjectionCheckpoint checkpoint = new ProjectionCheckpoint(11L, TOPIC_ID, null, 3L);

            assertEquals(new ProjectionSeekDecision(2L, TOPIC_ID, 11L),
                    ProjectionCatchUpPolicy.seekDecision(checkpoint, 2L, 10L, TOPIC_ID));
        }

        @Test
        void offsetAtBeginning_seeksCheckpoint() {
            ProjectionCheckpoint checkpoint = new ProjectionCheckpoint(2L, TOPIC_ID, null, null);

            assertEquals(new ProjectionSeekDecision(2L, TOPIC_ID, null),
                    ProjectionCatchUpPolicy.seekDecision(checkpoint, 2L, 10L, TOPIC_ID));
        }

        @Test
        void offsetAtEnd_seeksCheckpoint() {
            ProjectionCheckpoint checkpoint = new ProjectionCheckpoint(10L, TOPIC_ID, null, 3L);

            assertEquals(new ProjectionSeekDecision(10L, TOPIC_ID, null),
                    ProjectionCatchUpPolicy.seekDecision(checkpoint, 2L, 10L, TOPIC_ID));
        }
    }

    @Nested
    class IsReady {

        private static final ProjectionBarrier BARRIER = new ProjectionBarrier(TOPIC_ID, 5L);
        private static final ProjectionCheckpoint CHECKPOINT = new ProjectionCheckpoint(5L, TOPIC_ID, null, 3L, null);
        private static final ProjectionBrokerRange RANGE = new ProjectionBrokerRange(0L, 5L);

        @Test
        void caughtUpPartition_isReady() {
            assertTrue(isReady(BARRIER, CHECKPOINT, RANGE));
        }

        @Test
        void noBrokerPartitions_isNotReady() {
            assertFalse(ProjectionCatchUpPolicy.isReady(Set.of(), TOPIC_ID, Map.of(), Map.of(), Map.of()));
        }

        @Test
        void missingBarrier_isNotReady() {
            assertFalse(ProjectionCatchUpPolicy
                    .isReady(Set.of(0), TOPIC_ID, Map.of(), Map.of(0, CHECKPOINT), Map.of(0, RANGE)));
        }

        @Test
        void missingCheckpoint_isNotReady() {
            assertFalse(ProjectionCatchUpPolicy
                    .isReady(Set.of(0), TOPIC_ID, Map.of(0, BARRIER), Map.of(), Map.of(0, RANGE)));
        }

        @Test
        void missingBrokerRange_isNotReady() {
            assertFalse(ProjectionCatchUpPolicy
                    .isReady(Set.of(0), TOPIC_ID, Map.of(0, BARRIER), Map.of(0, CHECKPOINT), Map.of()));
        }

        @Test
        void barrierFromOtherTopic_isNotReady() {
            assertFalse(isReady(new ProjectionBarrier(OTHER_TOPIC_ID, 5L), CHECKPOINT, RANGE));
        }

        @Test
        void checkpointFromOtherTopic_isNotReady() {
            assertFalse(isReady(BARRIER, new ProjectionCheckpoint(5L, OTHER_TOPIC_ID, null, 3L, null), RANGE));
        }

        @Test
        void invalidCheckpointOffset_isNotReady() {
            assertFalse(isReady(BARRIER, new ProjectionCheckpoint(5L, TOPIC_ID, 1L, 3L, null), RANGE));
        }

        @Test
        void missingSnapshotCompletion_isNotReady() {
            assertFalse(isReady(BARRIER, new ProjectionCheckpoint(5L, TOPIC_ID, null, null, null), RANGE));
        }

        @Test
        void invalidRecordOffset_isNotReady() {
            assertFalse(isReady(BARRIER, new ProjectionCheckpoint(5L, TOPIC_ID, null, 3L, 4L), RANGE));
        }

        @Test
        void checkpointNotPastSnapshotMarker_isNotReady() {
            assertFalse(isReady(BARRIER, new ProjectionCheckpoint(5L, TOPIC_ID, null, 5L, null), RANGE));
        }

        @Test
        void barrierAheadOfBrokerEnd_isNotReady() {
            assertFalse(isReady(new ProjectionBarrier(TOPIC_ID, 6L), CHECKPOINT, RANGE));
        }

        @Test
        void snapshotMarkerBeforeBrokerBeginning_isNotReady() {
            assertFalse(isReady(BARRIER, CHECKPOINT, new ProjectionBrokerRange(4L, 5L)));
        }

        @Test
        void checkpointBehindBrokerEnd_isNotReady() {
            assertFalse(isReady(new ProjectionBarrier(TOPIC_ID, 4L),
                    new ProjectionCheckpoint(4L, TOPIC_ID, null, 3L, null),
                    RANGE));
        }

        @Test
        void checkpointAheadOfBrokerEnd_isNotReady() {
            assertFalse(isReady(BARRIER, new ProjectionCheckpoint(6L, TOPIC_ID, null, 3L, null), RANGE));
        }

        @Test
        void oneLaggingPartition_isNotReady() {
            assertFalse(ProjectionCatchUpPolicy.isReady(Set.of(0, 1),
                    TOPIC_ID,
                    Map.of(0, BARRIER, 1, BARRIER),
                    Map.of(0, CHECKPOINT, 1, new ProjectionCheckpoint(4L, TOPIC_ID, null, 3L, null)),
                    Map.of(0, RANGE, 1, RANGE)));
        }

        private boolean isReady(ProjectionBarrier barrier,
                ProjectionCheckpoint checkpoint,
                ProjectionBrokerRange range) {
            return ProjectionCatchUpPolicy
                    .isReady(Set.of(0), TOPIC_ID, Map.of(0, barrier), Map.of(0, checkpoint), Map.of(0, range));
        }
    }
}
