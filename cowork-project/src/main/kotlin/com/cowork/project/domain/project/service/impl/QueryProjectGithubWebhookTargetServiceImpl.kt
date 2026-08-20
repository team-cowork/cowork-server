package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.project.presentation.data.response.ProjectGithubWebhookTargetResDto
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.project.service.QueryProjectGithubWebhookTargetService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class QueryProjectGithubWebhookTargetServiceImpl(
    private val projectRepository: ProjectRepository,
) : QueryProjectGithubWebhookTargetService {

    @Transactional(readOnly = true)
    override fun execute(owner: String, repo: String): ProjectGithubWebhookTargetResDto {
        val githubRepoUrl = "https://github.com/$owner/$repo"
        val project = projectRepository.findByGithubRepoUrl(githubRepoUrl)
            ?: throw ExpectedException("연결된 프로젝트를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        val channelId = project.githubWebhookChannelId
            ?: throw ExpectedException("GitHub 알림 채널이 설정되지 않았습니다.", HttpStatus.NOT_FOUND)

        return ProjectGithubWebhookTargetResDto(teamId = project.teamId, projectId = project.id, channelId = channelId)
    }
}
