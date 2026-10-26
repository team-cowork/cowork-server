package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestResDto
import com.cowork.project.domain.github.service.GithubAppCallExecutor
import com.cowork.project.domain.github.service.GithubPullRequestReader
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.QueryPullRequestDetailService
import org.springframework.stereotype.Service

/**
 * PR 상세는 projection으로 채우고, GitHub이 요청 시점마다 재계산해 projection으로 재구성할 수 없는
 * `mergeable`/`mergeableState`/`reviewDecision`만 `GithubAppClient`로 직접 조회해 합친다.
 * projection을 먼저 확인하므로, 동기화 전(503)이거나 없는 PR(404)이면 GitHub을 호출하지 않는다.
 *
 * GitHub 응답(read-timeout 10초)을 기다리는 동안 DB 커넥션을 잡지 않도록 서비스 트랜잭션을 두지 않는다.
 * 권한·레포 해석과 projection 조회는 각자 짧은 읽기 트랜잭션에서 끝난다.
 */
@Service
class QueryPullRequestDetailServiceImpl(
    private val repoAccessResolver: GithubRepoAccessResolver,
    private val pullRequestReader: GithubPullRequestReader,
    private val callExecutor: GithubAppCallExecutor,
    private val githubAppClient: GithubAppClient,
) : QueryPullRequestDetailService {

    override fun execute(userId: Long, projectId: Long, repoId: Long, prNumber: Int): GithubPullRequestResDto {
        val repo = repoAccessResolver.resolveForRead(userId, projectId, repoId)
        val pullRequest = pullRequestReader.findPullRequest(repo.owner, repo.repo, prNumber)
        val liveStatus = callExecutor.execute {
            githubAppClient.getPullRequestLiveStatus(repo.owner, repo.repo, prNumber)
        }
        return GithubPullRequestResDto(
            number = pullRequest.number,
            title = pullRequest.title,
            body = pullRequest.body,
            author = pullRequest.author,
            state = pullRequest.state,
            mergeable = liveStatus.mergeable,
            mergeableState = liveStatus.mergeableState,
            reviewDecision = liveStatus.reviewDecision,
            headRef = pullRequest.headRef,
            baseRef = pullRequest.baseRef,
            htmlUrl = pullRequest.htmlUrl,
        )
    }
}
