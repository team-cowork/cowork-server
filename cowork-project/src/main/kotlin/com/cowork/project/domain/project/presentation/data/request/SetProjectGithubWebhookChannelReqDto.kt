package com.cowork.project.domain.project.presentation.data.request

import io.swagger.v3.oas.annotations.media.Schema

data class SetProjectGithubWebhookChannelReqDto(
    @param:Schema(description = "GitHub 알림을 수신할 채널 ID", example = "10", required = true)
    val channelId: Long,
)
