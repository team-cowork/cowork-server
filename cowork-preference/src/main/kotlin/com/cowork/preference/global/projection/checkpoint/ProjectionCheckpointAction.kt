package com.cowork.preference.global.projection.checkpoint

enum class ProjectionCheckpointAction {
    INITIALIZE,
    SEEK,
    BLOCK_BEHIND_LOG_START,
    BLOCK_AFTER_LOG_REWIND,
    BLOCK_TOPIC_RECREATED,
    BLOCK_INVALID_CHECKPOINT,
}
