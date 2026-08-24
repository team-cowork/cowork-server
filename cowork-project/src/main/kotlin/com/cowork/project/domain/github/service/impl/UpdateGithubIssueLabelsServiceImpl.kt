package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.client.GithubAppUpdateIssueLabelsReqDto
import com.cowork.project.domain.github.presentation.data.request.UpdateGithubIssueLabelsReqDto
import com.cowork.project.domain.github.presentation.data.response.GithubIssueResDto
import com.cowork.project.domain.github.service.GithubAppCallExecutor
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.UpdateGithubIssueLabelsService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UpdateGithubIssueLabelsServiceImpl(
    private val repoAccessResolver: GithubRepoAccessResolver,
    private val callExecutor: GithubAppCallExecutor,
    private val githubAppClient: GithubAppClient,
) : UpdateGithubIssueLabelsService {

    @Transactional(readOnly = true)
    override fun execute(
        userId: Long,
        projectId: Long,
        repoId: Long,
        issueNumber: Int,
        request: UpdateGithubIssueLabelsReqDto,
    ): GithubIssueResDto {
        val repo = repoAccessResolver.resolveForModify(userId, projectId, repoId)
        return callExecutor.execute {
            githubAppClient.updateIssueLabels(
                repo.owner,
                repo.repo,
                issueNumber,
                GithubAppUpdateIssueLabelsReqDto(labels = request.labels),
            )
        }
    }
}
