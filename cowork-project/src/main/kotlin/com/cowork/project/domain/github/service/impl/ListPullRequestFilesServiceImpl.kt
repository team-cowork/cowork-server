package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestFileResDto
import com.cowork.project.domain.github.service.GithubAppCallExecutor
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.ListPullRequestFilesService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ListPullRequestFilesServiceImpl(
    private val repoAccessResolver: GithubRepoAccessResolver,
    private val callExecutor: GithubAppCallExecutor,
    private val githubAppClient: GithubAppClient,
) : ListPullRequestFilesService {

    @Transactional(readOnly = true)
    override fun execute(userId: Long, projectId: Long, repoId: Long, prNumber: Int): List<GithubPullRequestFileResDto> {
        val repo = repoAccessResolver.resolveForRead(userId, projectId, repoId)
        return callExecutor.execute { githubAppClient.listPullRequestFiles(repo.owner, repo.repo, prNumber) }
    }
}
