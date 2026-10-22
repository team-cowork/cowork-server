package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestResDto
import com.cowork.project.domain.github.service.GithubAppCallExecutor
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.QueryPullRequestDetailService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * `mergeable`/`mergeableState`/`reviewDecision`은 GitHub이 그때그때 비동기로 재계산하는 live
 * 상태라 projection으로 재구성할 수 없다 — 이 조회만 예외로 `GithubAppClient`를 직접 호출한다.
 */
@Service
class QueryPullRequestDetailServiceImpl(
    private val repoAccessResolver: GithubRepoAccessResolver,
    private val callExecutor: GithubAppCallExecutor,
    private val githubAppClient: GithubAppClient,
) : QueryPullRequestDetailService {

    @Transactional(readOnly = true)
    override fun execute(userId: Long, projectId: Long, repoId: Long, prNumber: Int): GithubPullRequestResDto {
        val repo = repoAccessResolver.resolveForRead(userId, projectId, repoId)
        return callExecutor.execute { githubAppClient.getPullRequest(repo.owner, repo.repo, prNumber) }
    }
}
