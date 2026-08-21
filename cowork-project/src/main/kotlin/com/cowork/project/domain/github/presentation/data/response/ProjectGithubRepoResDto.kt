package com.cowork.project.domain.github.presentation.data.response

import com.cowork.project.domain.github.entity.ProjectGithubRepo
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "프로젝트에 등록된 GitHub 레포지토리")
data class ProjectGithubRepoResDto(
    @field:Schema(description = "등록 ID", example = "1")
    val id: Long,
    @field:Schema(description = "GitHub 레포지토리 URL", example = "https://github.com/my-org/my-repo")
    val githubRepoUrl: String,
    @field:Schema(description = "GitHub 알림을 수신할 채널 ID", example = "10")
    val githubWebhookChannelId: Long?,
) {
    companion object {
        fun of(entity: ProjectGithubRepo) = ProjectGithubRepoResDto(
            id = entity.id,
            githubRepoUrl = entity.githubRepoUrl,
            githubWebhookChannelId = entity.githubWebhookChannelId,
        )
    }
}
