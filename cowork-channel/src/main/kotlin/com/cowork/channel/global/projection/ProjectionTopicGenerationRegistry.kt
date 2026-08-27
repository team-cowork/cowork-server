package com.cowork.channel.global.projection

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class ProjectionTopicGenerationRegistry {
    private val topicIds = ConcurrentHashMap<ProjectionStream, String>()

    fun markAssigned(stream: ProjectionStream, topicId: String) {
        topicIds[stream] = topicId
    }

    fun markRevoked(stream: ProjectionStream) {
        topicIds.remove(stream)
    }

    fun topicId(stream: ProjectionStream): String? = topicIds[stream]
}
