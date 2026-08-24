package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.github.repository.ProjectGithubRepoRepository
import com.cowork.project.domain.github.service.GithubLabelPolicyReader
import com.cowork.project.domain.project.presentation.data.response.ProjectGithubLabelPolicyTargetResDto
import com.cowork.project.domain.project.service.QueryProjectGithubLabelPolicyTargetService
import org.springframework.stereotype.Service

@Service
class QueryProjectGithubLabelPolicyTargetServiceImpl(
    private val projectGithubRepoRepository: ProjectGithubRepoRepository,
    private val labelPolicyReader: GithubLabelPolicyReader,
) : QueryProjectGithubLabelPolicyTargetService {

    /**
     * 레포 연결은 팀 단위로만 중복이 방지되므로(서로 다른 팀이 같은 레포를 연결할 수 있음),
     * owner/repo 하나에 여러 프로젝트(팀)의 정책이 매칭될 수 있다 — 매칭되는 것을 전부 반환한다.
     * 매칭된 레포 수만큼 cowork-preference를 개별 호출하지 않도록 벌크 조회 API로 한 번에 가져온다.
     * DB 조회(findAllByGithubRepoUrl)는 리포지토리 메서드 자체가 트랜잭션을 가지므로 별도 트랜잭션으로
     * 원격 호출 구간까지 묶어 커넥션을 오래 붙잡을 필요가 없다.
     */
    override fun execute(owner: String, repo: String): List<ProjectGithubLabelPolicyTargetResDto> {
        val githubRepoUrl = "https://github.com/$owner/$repo"
        val repoLinks = projectGithubRepoRepository.findAllByGithubRepoUrl(githubRepoUrl)
        val autoApplyByRepoId = labelPolicyReader.readAutoApplyBulk(repoLinks.map { it.id })
        return repoLinks.map { repoLink ->
            ProjectGithubLabelPolicyTargetResDto(
                teamId = repoLink.teamId,
                projectId = repoLink.projectId,
                repoId = repoLink.id,
                autoApply = autoApplyByRepoId.getValue(repoLink.id),
            )
        }
    }
}
