package com.cowork.project.domain.github.event

import java.time.Instant

enum class ProjectGithubRepoEventType { UPSERT, DELETE }

data class ProjectGithubRepoEvent(
    val schemaVersion: Int = 1,
    val eventType: ProjectGithubRepoEventType,
    val repoId: Long,
    val projectId: Long,
    val teamId: Long,
    val githubRepoUrl: String?,
    val owner: String,
    val repo: String,
    val webhookChannelId: Long?,
    val occurredAt: Instant,
    val snapshot: Boolean = false,
)
