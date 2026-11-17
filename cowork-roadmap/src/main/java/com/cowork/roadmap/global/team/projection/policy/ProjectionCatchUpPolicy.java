package com.cowork.roadmap.global.team.projection.policy;

import java.util.Map;
import java.util.Set;

import com.cowork.roadmap.global.team.projection.model.ProjectionBarrier;
import com.cowork.roadmap.global.team.projection.model.ProjectionBrokerRange;
import com.cowork.roadmap.global.team.projection.model.ProjectionCheckpoint;
import com.cowork.roadmap.global.team.projection.model.ProjectionSeekDecision;

public final class ProjectionCatchUpPolicy {

    private ProjectionCatchUpPolicy() {
    }

    public static ProjectionSeekDecision seekDecision(ProjectionCheckpoint checkpoint,
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

    public static boolean isReady(Set<Integer> brokerPartitions,
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
                    || barrier.targetOffset() > range.endOffset()
                    || checkpoint.snapshotCompletedOffset() < range.beginningOffset()
                    || checkpoint.nextOffset() != range.endOffset()) {
                return false;
            }
        }
        return true;
    }
}
