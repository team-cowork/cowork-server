package com.cowork.roadmap.global.team.projection.topic;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

@Component
public class ProjectionTopicGenerationRegistry {

    private final AtomicReference<String> topicId = new AtomicReference<>();

    public void markAssigned(String currentTopicId) {
        topicId.set(currentTopicId);
    }

    public void markRevoked() {
        topicId.set(null);
    }

    public String requireTopicId() {
        String current = topicId.get();
        if (current == null) {
            throw new IllegalStateException("Kafka projection topic generation is not initialized");
        }
        return current;
    }
}
