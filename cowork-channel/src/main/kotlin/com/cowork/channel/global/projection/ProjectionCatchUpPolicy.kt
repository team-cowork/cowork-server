package com.cowork.channel.global.projection

data class ProjectionStream(val consumerGroup: String, val topic: String, val expectedSource: String?)

data class ProjectionCheckpoint(
    val nextOffset: Long,
    val topicId: String,
    val invalidCheckpointOffset: Long? = null,
    val snapshotCompletedOffset: Long? = null,
    val invalidRecordOffset: Long? = null,
)

data class ProjectionBarrier(val topicId: String, val targetOffset: Long)

data class ProjectionPartitionRange(val partition: Int, val beginningOffset: Long, val endOffset: Long)

data class ProjectionBrokerRange(val beginningOffset: Long, val endOffset: Long)

data class ProjectionTopicState(val topicId: String, val ranges: Map<Int, ProjectionBrokerRange>)

data class ProjectionSeekDecision(val seekOffset: Long, val topicId: String, val invalidCheckpointOffset: Long?)

object ProjectionCatchUpPolicy {
    fun seekDecision(
        checkpoint: ProjectionCheckpoint?,
        beginningOffset: Long,
        endOffset: Long,
        currentTopicId: String,
    ): ProjectionSeekDecision {
        if (checkpoint == null) return ProjectionSeekDecision(beginningOffset, currentTopicId, null)
        if (checkpoint.topicId != currentTopicId) {
            return ProjectionSeekDecision(beginningOffset, currentTopicId, checkpoint.nextOffset)
        }
        if (checkpoint.invalidCheckpointOffset != null) {
            val safeOffset = checkpoint.nextOffset.takeIf { it in beginningOffset..endOffset } ?: beginningOffset
            return ProjectionSeekDecision(safeOffset, currentTopicId, checkpoint.invalidCheckpointOffset)
        }
        if (checkpoint.nextOffset < beginningOffset || checkpoint.nextOffset > endOffset) {
            return ProjectionSeekDecision(beginningOffset, currentTopicId, checkpoint.nextOffset)
        }
        return ProjectionSeekDecision(checkpoint.nextOffset, currentTopicId, null)
    }

    fun isReady(
        requiredStreams: Set<ProjectionStream>,
        barriers: Map<ProjectionStream, Map<Int, ProjectionBarrier>>,
        checkpoints: Map<ProjectionStream, Map<Int, ProjectionCheckpoint>>,
    ): Boolean = requiredStreams.all { stream ->
        val streamBarriers = barriers[stream].orEmpty()
        val streamCheckpoints = checkpoints[stream].orEmpty()
        streamBarriers.isNotEmpty() &&
            streamBarriers.all { (partition, barrier) ->
                val checkpoint = streamCheckpoints[partition]
                checkpoint != null &&
                    checkpoint.topicId == barrier.topicId &&
                    checkpoint.invalidCheckpointOffset == null &&
                    checkpoint.invalidRecordOffset == null &&
                    checkpoint.snapshotCompletedOffset != null &&
                    checkpoint.nextOffset > checkpoint.snapshotCompletedOffset &&
                    checkpoint.nextOffset >= barrier.targetOffset
            }
    }

    fun matchesBrokerState(
        requiredStreams: Set<ProjectionStream>,
        barriers: Map<ProjectionStream, Map<Int, ProjectionBarrier>>,
        checkpoints: Map<ProjectionStream, Map<Int, ProjectionCheckpoint>>,
        topicStates: Map<String, ProjectionTopicState>,
    ): Boolean = requiredStreams.all { stream ->
        val topicState = topicStates[stream.topic] ?: return@all false
        val streamBarriers = barriers[stream].orEmpty()
        val streamCheckpoints = checkpoints[stream].orEmpty()
        val brokerPartitions = topicState.ranges.keys
        if (streamBarriers.keys != brokerPartitions || streamCheckpoints.keys != brokerPartitions) return@all false

        topicState.ranges.all { (partition, range) ->
            val barrier = streamBarriers.getValue(partition)
            val checkpoint = streamCheckpoints.getValue(partition)
            barrier.topicId == topicState.topicId &&
                checkpoint.topicId == topicState.topicId &&
                checkpoint.snapshotCompletedOffset?.let { it >= range.beginningOffset } == true &&
                barrier.targetOffset <= range.endOffset &&
                checkpoint.nextOffset == range.endOffset
        }
    }
}
