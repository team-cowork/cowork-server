package com.cowork.preference.global.projection.checkpoint

data class ProjectionAssignmentResult(val seekOffsets: Map<Int, Long>, val invalidCheckpoint: Boolean)
