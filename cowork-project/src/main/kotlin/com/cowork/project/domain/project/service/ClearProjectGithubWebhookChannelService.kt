package com.cowork.project.domain.project.service

import com.cowork.project.domain.github.presentation.data.response.ProjectGithubRepoResDto

interface ClearProjectGithubWebhookChannelService {
    fun execute(userId: Long, projectId: Long, repoId: Long): ProjectGithubRepoResDto
}
