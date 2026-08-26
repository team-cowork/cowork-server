package com.cowork.preference.messaging

import com.cowork.preference.repository.ProjectionCheckpoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProjectionCheckpointPolicyTest {
    private val topicId = "topic-id"

    @Test
    fun `missing checkpoint starts at the topic beginning`() {
        val resolution = ProjectionCheckpointPolicy.resolve(null, 4, 12, topicId)

        assertEquals(ProjectionCheckpointAction.INITIALIZE, resolution.action)
        assertEquals(4L, resolution.seekOffset)
    }

    @Test
    fun `valid database checkpoint replaces the broker group offset`() {
        val resolution = ProjectionCheckpointPolicy.resolve(checkpoint(9), 4, 12, topicId)

        assertEquals(ProjectionCheckpointAction.SEEK, resolution.action)
        assertEquals(9L, resolution.seekOffset)
    }

    @Test
    fun `checkpoint behind retained history seeks safely but keeps readiness blocked`() {
        val resolution = ProjectionCheckpointPolicy.resolve(checkpoint(3), 8, 12, topicId)

        assertEquals(ProjectionCheckpointAction.BLOCK_BEHIND_LOG_START, resolution.action)
        assertEquals(8L, resolution.seekOffset)
        assertEquals(3L, resolution.invalidCheckpointOffset)
    }

    @Test
    fun `checkpoint ahead of the log seeks safely but keeps readiness blocked`() {
        val resolution = ProjectionCheckpointPolicy.resolve(checkpoint(99), 0, 3, topicId)

        assertEquals(ProjectionCheckpointAction.BLOCK_AFTER_LOG_REWIND, resolution.action)
        assertEquals(0L, resolution.seekOffset)
    }

    @Test
    fun `same-name topic recreation blocks even when numeric offsets overlap`() {
        val resolution = ProjectionCheckpointPolicy.resolve(checkpoint(2, "old-topic-id"), 0, 10, topicId)

        assertEquals(ProjectionCheckpointAction.BLOCK_TOPIC_RECREATED, resolution.action)
        assertEquals(0L, resolution.seekOffset)
        assertEquals(2L, resolution.invalidCheckpointOffset)
    }

    private fun checkpoint(offset: Long, storedTopicId: String = topicId) = ProjectionCheckpoint(
        consumerGroup = "group",
        topic = "team.member.event",
        partition = 0,
        nextOffset = offset,
        topicId = storedTopicId,
    )
}
