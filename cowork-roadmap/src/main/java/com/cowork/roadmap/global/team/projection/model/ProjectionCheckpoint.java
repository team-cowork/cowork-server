package com.cowork.roadmap.global.team.projection.model;

public record ProjectionCheckpoint(long nextOffset, String topicId, Long invalidCheckpointOffset,
        Long snapshotCompletedOffset, Long invalidRecordOffset) {

    public ProjectionCheckpoint(long nextOffset,
            String topicId,
            Long invalidCheckpointOffset,
            Long snapshotCompletedOffset) {
        this(nextOffset, topicId, invalidCheckpointOffset, snapshotCompletedOffset, null);
    }
}
