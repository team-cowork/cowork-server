package com.cowork.project.domain.project.service

import com.cowork.project.domain.project.presentation.data.request.SetProjectGithubWebhookChannelReqDto
import com.cowork.project.domain.project.presentation.data.response.ProjectDetailResDto

interface SetProjectGithubWebhookChannelService {
    fun execute(userId: Long, projectId: Long, request: SetProjectGithubWebhookChannelReqDto): ProjectDetailResDto
}
