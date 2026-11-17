package com.cowork.roadmap.global.team.projection.model;

import java.util.Map;

public record ProjectionTopicState(String topicId, Map<Integer, ProjectionBrokerRange> ranges) {
}
