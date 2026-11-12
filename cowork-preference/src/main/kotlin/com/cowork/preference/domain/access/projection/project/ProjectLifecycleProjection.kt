package com.cowork.preference.domain.access.projection.project

import java.time.Instant

data class ProjectLifecycleProjection(
    val projectId: Long,
    val teamId: Long,
    val deleted: Boolean,
    val sourceOccurredAt: Instant,
    val createdBy: Long? = null,
    val isPrivate: Boolean = true,
)
