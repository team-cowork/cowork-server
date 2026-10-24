package com.cowork.project.domain.project.event

import com.fasterxml.jackson.annotation.JsonProperty

import com.cowork.project.domain.project.entity.ProjectStatus
import java.time.Instant

enum class ProjectEventType { CREATED, UPDATED, DELETED }

data class ProjectEvent(
    val eventType: ProjectEventType,
    val projectId: Long,
    val teamId: Long,
    val name: String,
    val description: String?,
    val status: ProjectStatus,
    val position: Int,
    val occurredAt: Instant,
    val snapshot: Boolean = false,
    val createdBy: Long? = null,
    @field:JsonProperty("isPrivate")
    val isPrivate: Boolean = true,
    val initialReadGrant: ProjectInitialReadGrant? = null,
)

/** Preference applies this ordinary read role once per project, including after snapshot replay. */
data class ProjectInitialReadGrant(
    val accountId: Long,
    val sourceMembershipVersion: Instant,
    val sourceAuthorizationVersion: Instant? = null,
)
