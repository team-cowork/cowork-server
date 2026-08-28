package com.cowork.roadmap.global.team;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

@Component
public class ProjectionTopicGenerationRegistry {

    private final AtomicReference<String> topicId = new AtomicReference<>();

    void markAssigned(String currentTopicId) {
        topicId.set(currentTopicId);
    }

    void markRevoked() {
        topicId.set(null);
    }

    String requireTopicId() {
        String current = topicId.get();
        if (current == null) {
            throw new IllegalStateException("Kafka projection topic generation is not initialized");
        }
        return current;
    }
}
