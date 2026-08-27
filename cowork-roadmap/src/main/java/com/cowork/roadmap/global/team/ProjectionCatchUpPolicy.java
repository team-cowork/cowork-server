package com.cowork.roadmap.global.team;

import java.util.Map;
import java.util.Set;

record ProjectionCheckpoint(long nextOffset, String topicId, Long invalidCheckpointOffset, Long snapshotCompletedOffset,
        Long invalidRecordOffset) {

    ProjectionCheckpoint(long nextOffset, String topicId, Long invalidCheckpointOffset, Long snapshotCompletedOffset) {
        this(nextOffset, topicId, invalidCheckpointOffset, snapshotCompletedOffset, null);
    }
}

record ProjectionBarrier(String topicId, long targetOffset) {
}

record ProjectionPartitionRange(int partition, long beginningOffset, long endOffset) {
}

record ProjectionBrokerRange(long beginningOffset, long endOffset) {
}

record ProjectionTopicState(String topicId, Map<Integer, ProjectionBrokerRange> ranges) {
}

record ProjectionSeekDecision(long seekOffset, String topicId, Long invalidCheckpointOffset) {
}

final class ProjectionCatchUpPolicy {

    private ProjectionCatchUpPolicy() {
    }

    static ProjectionSeekDecision seekDecision(ProjectionCheckpoint checkpoint,
            long beginningOffset,
            long endOffset,
            String currentTopicId) {
        if (checkpoint == null) {
            return new ProjectionSeekDecision(beginningOffset, currentTopicId, null);
        }
        if (!checkpoint.topicId().equals(currentTopicId)) {
            return new ProjectionSeekDecision(beginningOffset, currentTopicId, checkpoint.nextOffset());
        }
        if (checkpoint.invalidCheckpointOffset() != null) {
            long safeOffset = checkpoint.nextOffset() >= beginningOffset && checkpoint.nextOffset() <= endOffset
                    ? checkpoint.nextOffset()
                    : beginningOffset;
            return new ProjectionSeekDecision(safeOffset, currentTopicId, checkpoint.invalidCheckpointOffset());
        }
        if (checkpoint.nextOffset() < beginningOffset || checkpoint.nextOffset() > endOffset) {
            return new ProjectionSeekDecision(beginningOffset, currentTopicId, checkpoint.nextOffset());
        }
        return new ProjectionSeekDecision(checkpoint.nextOffset(), currentTopicId, null);
    }

    static boolean isReady(Set<Integer> brokerPartitions,
            String currentTopicId,
            Map<Integer, ProjectionBarrier> barriers,
            Map<Integer, ProjectionCheckpoint> checkpoints,
            Map<Integer, ProjectionBrokerRange> brokerRanges) {
        if (brokerPartitions.isEmpty() || !barriers.keySet().equals(brokerPartitions)
                || !checkpoints.keySet().equals(brokerPartitions) || !brokerRanges.keySet().equals(brokerPartitions)) {
            return false;
        }
        for (Integer partition : brokerPartitions) {
            ProjectionBarrier barrier = barriers.get(partition);
            ProjectionCheckpoint checkpoint = checkpoints.get(partition);
            ProjectionBrokerRange range = brokerRanges.get(partition);
            if (!barrier.topicId().equals(currentTopicId) || !checkpoint.topicId().equals(currentTopicId)
                    || checkpoint.invalidCheckpointOffset() != null || checkpoint.snapshotCompletedOffset() == null
                    || checkpoint.invalidRecordOffset() != null
                    || checkpoint.nextOffset() <= checkpoint.snapshotCompletedOffset()
                    || checkpoint.nextOffset() < barrier.targetOffset() || barrier.targetOffset() > range.endOffset()
                    || checkpoint.snapshotCompletedOffset() < range.beginningOffset()
                    || checkpoint.nextOffset() < range.beginningOffset()
                    || checkpoint.nextOffset() != range.endOffset()) {
                return false;
            }
        }
        return true;
    }
}
