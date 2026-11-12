package com.cowork.preference.global.projection.checkpoint

object ProjectionCheckpointPolicy {
    fun resolve(
        stored: ProjectionCheckpoint?,
        beginningOffset: Long,
        endOffset: Long,
        currentTopicId: String,
    ): ProjectionCheckpointResolution {
        require(beginningOffset >= 0) { "beginningOffset must not be negative" }
        require(endOffset >= beginningOffset) { "endOffset must not precede beginningOffset" }
        return when {
            stored == null -> resolution(ProjectionCheckpointAction.INITIALIZE, beginningOffset, currentTopicId, null)

            stored.topicId != currentTopicId -> resolution(
                ProjectionCheckpointAction.BLOCK_TOPIC_RECREATED,
                beginningOffset,
                currentTopicId,
                stored.nextOffset,
            )

            stored.invalidCheckpointOffset != null -> resolution(
                ProjectionCheckpointAction.BLOCK_INVALID_CHECKPOINT,
                stored.nextOffset.takeIf { it in beginningOffset..endOffset } ?: beginningOffset,
                currentTopicId,
                stored.invalidCheckpointOffset,
            )

            stored.nextOffset < beginningOffset -> resolution(
                ProjectionCheckpointAction.BLOCK_BEHIND_LOG_START,
                beginningOffset,
                currentTopicId,
                stored.nextOffset,
            )

            stored.nextOffset > endOffset -> resolution(
                ProjectionCheckpointAction.BLOCK_AFTER_LOG_REWIND,
                beginningOffset,
                currentTopicId,
                stored.nextOffset,
            )

            else -> resolution(ProjectionCheckpointAction.SEEK, stored.nextOffset, currentTopicId, null)
        }
    }

    private fun resolution(
        action: ProjectionCheckpointAction,
        seekOffset: Long,
        topicId: String,
        invalidCheckpointOffset: Long?,
    ) = ProjectionCheckpointResolution(action, seekOffset, topicId, invalidCheckpointOffset)
}
