package com.cowork.preference.global.projection.checkpoint

data class ProjectionCheckpoint(
    val consumerGroup: String,
    val topic: String,
    val partition: Int,
    val nextOffset: Long,
    val topicId: String,
    val invalidCheckpointOffset: Long? = null,
    val invalidRecordOffset: Long? = null,
    val snapshotCompletedOffset: Long? = null,
)
