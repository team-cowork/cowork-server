package com.cowork.project.domain.project.service

import com.cowork.project.domain.github.presentation.data.response.ProjectGithubRepoResDto
import com.cowork.project.domain.project.presentation.data.request.SetProjectGithubWebhookChannelReqDto

interface SetProjectGithubWebhookChannelService {
    fun execute(
        userId: Long,
        projectId: Long,
        repoId: Long,
        request: SetProjectGithubWebhookChannelReqDto,
    ): ProjectGithubRepoResDto
}
