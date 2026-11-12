package com.cowork.preference.global.projection.readiness

import com.cowork.preference.global.projection.broker.ProjectionTopicState
import com.cowork.preference.global.projection.checkpoint.ProjectionBarrier
import com.cowork.preference.global.projection.checkpoint.ProjectionCheckpoint

class ProjectionReadiness : ProjectionReadinessView {
    @Volatile
    private var snapshot = ProjectionReadinessSnapshot(
        ready = false,
        reason = "Kafka assignment has not initialized shared projection state",
        initialized = false,
    )

    override val isReady: Boolean
        get() = snapshot.ready

    override fun snapshot(): ProjectionReadinessSnapshot = snapshot

    fun markInitializing(reason: String) {
        snapshot = ProjectionReadinessSnapshot(false, reason, false)
    }

    fun markInitialized() {
        snapshot = ProjectionReadinessSnapshot(false, "waiting for shared projection checkpoints", true)
    }

    fun evaluate(
        barriers: Map<Int, ProjectionBarrier>,
        checkpoints: Map<Int, ProjectionCheckpoint>,
        topicState: ProjectionTopicState,
    ) {
        val current = snapshot
        if (!current.initialized) return
        val evaluation = ProjectionReadinessEvaluator.evaluate(barriers, checkpoints, topicState)
        snapshot = ProjectionReadinessSnapshot(evaluation.ready, evaluation.reason, true)
    }

    fun markUnavailable(reason: String) {
        snapshot = snapshot.copy(ready = false, reason = reason)
    }
}
