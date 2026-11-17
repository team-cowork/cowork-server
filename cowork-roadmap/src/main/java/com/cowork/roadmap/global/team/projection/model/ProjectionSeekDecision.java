package com.cowork.roadmap.global.team.projection.model;

public record ProjectionSeekDecision(long seekOffset, String topicId, Long invalidCheckpointOffset) {
}
