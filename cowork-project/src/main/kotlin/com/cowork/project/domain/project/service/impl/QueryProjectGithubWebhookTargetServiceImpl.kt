package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.project.presentation.data.response.ProjectGithubWebhookTargetResDto
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.project.service.QueryProjectGithubWebhookTargetService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class QueryProjectGithubWebhookTargetServiceImpl(
    private val projectRepository: ProjectRepository,
) : QueryProjectGithubWebhookTargetService {

    /**
     * 레포 연결 자체는 팀 단위로만 중복이 방지되므로(서로 다른 팀이 같은 레포를 연결할 수 있음),
     * owner/repo 하나에 여러 프로젝트(팀)가 매칭될 수 있다 — 알림 채널이 설정된 것만 전부 반환한다.
     */
    @Transactional(readOnly = true)
    override fun execute(owner: String, repo: String): List<ProjectGithubWebhookTargetResDto> {
        val githubRepoUrl = "https://github.com/$owner/$repo"
        return projectRepository.findAllByGithubRepoUrl(githubRepoUrl)
            .mapNotNull { project ->
                project.githubWebhookChannelId?.let { channelId ->
                    ProjectGithubWebhookTargetResDto(teamId = project.teamId, projectId = project.id, channelId = channelId)
                }
            }
    }
}
