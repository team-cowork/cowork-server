package com.cowork.preference.domain

import java.time.Instant

data class ProjectLifecycleProjection(
    val projectId: Long,
    val teamId: Long,
    val deleted: Boolean,
    val sourceOccurredAt: Instant,
    val createdBy: Long? = null,
)
