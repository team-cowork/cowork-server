package com.cowork.preference.messaging

import com.cowork.preference.repository.ProjectionBarrier
import com.cowork.preference.repository.ProjectionCheckpoint

data class ProjectionReadinessEvaluation(val ready: Boolean, val reason: String)

object ProjectionReadinessEvaluator {
    fun evaluate(
        barriers: Map<Int, ProjectionBarrier>,
        checkpoints: Map<Int, ProjectionCheckpoint>,
        topicState: ProjectionTopicState,
    ): ProjectionReadinessEvaluation {
        val partitions = topicState.ranges.keys
        if (partitions.isEmpty()) return unavailable("topic has no partitions")
        if (barriers.keys != partitions || checkpoints.keys != partitions) {
            return unavailable("shared projection state does not match topic partition topology")
        }

        partitions.sorted().forEach { partition ->
            val barrier = requireNotNull(barriers[partition])
            val checkpoint = requireNotNull(checkpoints[partition])
            val range = requireNotNull(topicState.ranges[partition])
            if (barrier.topicId != topicState.topicId || checkpoint.topicId != topicState.topicId) {
                return unavailable("topic generation changed for partition $partition")
            }
            if (checkpoint.invalidCheckpointOffset != null) {
                return unavailable("checkpoint requires manual rebuild for partition $partition")
            }
            if (checkpoint.invalidRecordOffset != null) {
                return unavailable("invalid record requires manual rebuild for partition $partition")
            }
            val markerOffset = checkpoint.snapshotCompletedOffset
                ?: return unavailable("snapshot completion marker missing for partition $partition")
            if (checkpoint.nextOffset <= markerOffset) {
                return unavailable("snapshot completion marker checkpoint is not atomic for partition $partition")
            }
            if (markerOffset < range.beginningOffset) {
                return unavailable("snapshot completion marker is behind partition $partition retained history")
            }
            if (checkpoint.nextOffset < range.beginningOffset) {
                return unavailable("checkpoint is behind partition $partition retained history")
            }
            if (checkpoint.nextOffset > range.endOffset) {
                return unavailable("checkpoint is ahead of partition $partition log end")
            }
            if (barrier.targetOffset > range.endOffset) {
                return unavailable("startup barrier is ahead of partition $partition log end")
            }
            if (checkpoint.nextOffset < barrier.targetOffset) {
                return unavailable("checkpoint has not reached partition $partition startup barrier")
            }
        }
        return ProjectionReadinessEvaluation(true, "projection caught up after source snapshot completion")
    }

    private fun unavailable(reason: String) = ProjectionReadinessEvaluation(false, reason)
}
data class ProjectionReadinessSnapshot(val ready: Boolean, val reason: String, val initialized: Boolean)

interface ProjectionReadinessView {
    val isReady: Boolean

    fun snapshot(): ProjectionReadinessSnapshot
}

/** 여러 projection이 모두 준비되어야 여는 readiness이며, 닫혀 있으면 첫 미준비 projection의 사유를 노출한다. */
class CombinedProjectionReadiness(private val projections: List<ProjectionReadinessView>) : ProjectionReadinessView {
    init {
        require(projections.isNotEmpty()) { "combined readiness requires at least one projection" }
    }

    override val isReady: Boolean
        get() = projections.all { it.isReady }

    override fun snapshot(): ProjectionReadinessSnapshot {
        val snapshots = projections.map(ProjectionReadinessView::snapshot)
        return snapshots.firstOrNull { !it.ready } ?: snapshots.first()
    }
}

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
