package com.cowork.preference.global.projection.checkpoint

data class ProjectionPartitionRange(val partition: Int, val beginningOffset: Long, val endOffset: Long)
