package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestFileResDto
import com.cowork.project.domain.github.service.GithubPullRequestReader
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.ListPullRequestFilesService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ListPullRequestFilesServiceImpl(
    private val githubRepoAccessResolver: GithubRepoAccessResolver,
    private val githubPullRequestReader: GithubPullRequestReader,
) : ListPullRequestFilesService {

    @Transactional(readOnly = true)
    override fun execute(
        userId: Long,
        projectId: Long,
        repoId: Long,
        prNumber: Int,
    ): List<GithubPullRequestFileResDto> {
        val repo = githubRepoAccessResolver.resolveForRead(userId, projectId, repoId)
        return githubPullRequestReader.listFiles(repo.owner, repo.repo, prNumber)
    }
}
