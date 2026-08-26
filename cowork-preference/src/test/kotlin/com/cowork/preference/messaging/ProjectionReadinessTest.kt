package com.cowork.preference.messaging

import com.cowork.preference.repository.ProjectionBarrier
import com.cowork.preference.repository.ProjectionCheckpoint
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProjectionReadinessTest {
    private val topicId = "topic-id"

    @Test
    fun `new empty topic is unavailable before snapshot completion marker`() {
        val evaluation = evaluate(
            barriers = mapOf(0 to ProjectionBarrier(topicId, 0)),
            checkpoints = mapOf(0 to checkpoint(0, markerOffset = null)),
            ranges = mapOf(0 to ProjectionBrokerRange(0, 0)),
        )

        assertFalse(evaluation.ready)
        assertTrue(evaluation.reason.contains("marker"))
    }

    @Test
    fun `every partition requires marker and startup barrier catch-up`() {
        val barriers = mapOf(0 to ProjectionBarrier(topicId, 1), 1 to ProjectionBarrier(topicId, 1))
        val missingMarker = evaluate(
            barriers,
            mapOf(0 to checkpoint(2, 1), 1 to checkpoint(2, null, partition = 1)),
            mapOf(0 to ProjectionBrokerRange(0, 2), 1 to ProjectionBrokerRange(0, 2)),
        )
        val caughtUp = evaluate(
            barriers,
            mapOf(0 to checkpoint(2, 1), 1 to checkpoint(2, 1, partition = 1)),
            mapOf(0 to ProjectionBrokerRange(0, 2), 1 to ProjectionBrokerRange(0, 2)),
        )

        assertFalse(missingMarker.ready)
        assertTrue(caughtUp.ready)
    }

    @Test
    fun `same-name topic recreation and retention gaps fail closed`() {
        val barrier = mapOf(0 to ProjectionBarrier(topicId, 4))
        val recreated = ProjectionReadinessEvaluator.evaluate(
            barrier,
            mapOf(0 to checkpoint(5, 4)),
            ProjectionTopicState("new-topic-id", mapOf(0 to ProjectionBrokerRange(0, 10))),
        )
        val retained = evaluate(
            barrier,
            mapOf(0 to checkpoint(5, 4)),
            mapOf(0 to ProjectionBrokerRange(6, 10)),
        )

        assertFalse(recreated.ready)
        assertFalse(retained.ready)
    }

    @Test
    fun `replica without local assignment becomes ready from shared durable state`() {
        val readiness = ProjectionReadiness()
        readiness.markInitialized()
        readiness.evaluate(
            barriers = mapOf(0 to ProjectionBarrier(topicId, 1)),
            checkpoints = mapOf(0 to checkpoint(2, 1)),
            topicState = ProjectionTopicState(topicId, mapOf(0 to ProjectionBrokerRange(0, 2))),
        )

        assertTrue(readiness.isReady)
    }

    private fun evaluate(
        barriers: Map<Int, ProjectionBarrier>,
        checkpoints: Map<Int, ProjectionCheckpoint>,
        ranges: Map<Int, ProjectionBrokerRange>,
    ) = ProjectionReadinessEvaluator.evaluate(barriers, checkpoints, ProjectionTopicState(topicId, ranges))

    private fun checkpoint(nextOffset: Long, markerOffset: Long?, partition: Int = 0) = ProjectionCheckpoint(
        consumerGroup = "group",
        topic = "team.member.event",
        partition = partition,
        nextOffset = nextOffset,
        topicId = topicId,
        snapshotCompletedOffset = markerOffset,
    )
}
