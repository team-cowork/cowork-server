package com.cowork.project.domain.project.presentation.data.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "GitHub 웹훅 알림 전달 대상 (내부 서비스용)")
data class ProjectGithubWebhookTargetResDto(
    @field:Schema(description = "팀 ID", example = "1")
    val teamId: Long,
    @field:Schema(description = "프로젝트 ID", example = "1")
    val projectId: Long,
    @field:Schema(description = "GitHub 알림을 수신할 채널 ID", example = "10")
    val channelId: Long,
)
