package com.cowork.preference.global.projection.checkpoint

data class ProjectionCheckpointResolution(
    val action: ProjectionCheckpointAction,
    val seekOffset: Long,
    val topicId: String,
    val invalidCheckpointOffset: Long?,
)
