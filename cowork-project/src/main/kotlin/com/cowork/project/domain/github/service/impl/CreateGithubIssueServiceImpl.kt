package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.presentation.data.request.CreateGithubIssueReqDto
import com.cowork.project.domain.github.presentation.data.response.GithubIssueResDto
import com.cowork.project.domain.github.service.CreateGithubIssueService
import com.cowork.project.domain.github.service.GithubAppCallExecutor
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CreateGithubIssueServiceImpl(
    private val repoAccessResolver: GithubRepoAccessResolver,
    private val callExecutor: GithubAppCallExecutor,
    private val githubAppClient: GithubAppClient,
) : CreateGithubIssueService {

    @Transactional(readOnly = true)
    override fun execute(userId: Long, projectId: Long, repoId: Long, request: CreateGithubIssueReqDto): GithubIssueResDto {
        val repo = repoAccessResolver.resolveForModify(userId, projectId, repoId)
        return callExecutor.execute {
            githubAppClient.createIssue(
                repo.owner,
                repo.repo,
                mapOf(
                    "title" to request.title,
                    "body" to request.body,
                    "labels" to listOfNotNull(request.label),
                ),
            )
        }
    }
}
