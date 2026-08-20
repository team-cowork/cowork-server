package com.cowork.project.domain.project.service

import com.cowork.project.domain.project.presentation.data.response.ProjectGithubWebhookTargetResDto

interface QueryProjectGithubWebhookTargetService {
    fun execute(owner: String, repo: String): ProjectGithubWebhookTargetResDto
}
