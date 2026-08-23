package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.github.repository.ProjectGithubRepoRepository
import com.cowork.project.domain.github.service.GithubLabelPolicyReader
import com.cowork.project.domain.project.presentation.data.response.ProjectGithubLabelPolicyTargetResDto
import com.cowork.project.domain.project.service.QueryProjectGithubLabelPolicyTargetService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class QueryProjectGithubLabelPolicyTargetServiceImpl(
    private val projectGithubRepoRepository: ProjectGithubRepoRepository,
    private val labelPolicyReader: GithubLabelPolicyReader,
) : QueryProjectGithubLabelPolicyTargetService {

    /**
     * 레포 연결은 팀 단위로만 중복이 방지되므로(서로 다른 팀이 같은 레포를 연결할 수 있음),
     * owner/repo 하나에 여러 프로젝트(팀)의 정책이 매칭될 수 있다 — 매칭되는 것을 전부 반환한다.
     */
    @Transactional(readOnly = true)
    override fun execute(owner: String, repo: String): List<ProjectGithubLabelPolicyTargetResDto> {
        val githubRepoUrl = "https://github.com/$owner/$repo"
        return projectGithubRepoRepository.findAllByGithubRepoUrl(githubRepoUrl)
            .map { repoLink ->
                ProjectGithubLabelPolicyTargetResDto(
                    teamId = repoLink.teamId,
                    projectId = repoLink.projectId,
                    repoId = repoLink.id,
                    autoApply = labelPolicyReader.readAutoApply(repoLink.id),
                )
            }
    }
}
