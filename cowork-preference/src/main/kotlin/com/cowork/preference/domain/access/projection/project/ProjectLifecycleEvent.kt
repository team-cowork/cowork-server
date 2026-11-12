package com.cowork.preference.domain.access.projection.project

import com.cowork.preference.domain.access.model.InitialProjectReadGrant
import java.time.Instant

/** project.event.v2에서 권한 판단에 필요한 소속 팀과 삭제 여부만 추린 것이다. */
data class ProjectLifecycleEvent(
    val projectId: Long,
    val teamId: Long,
    val deleted: Boolean,
    val occurredAt: Instant,
    val createdBy: Long? = null,
    val isPrivate: Boolean = true,
    val initialReadGrant: InitialProjectReadGrant? = null,
)
