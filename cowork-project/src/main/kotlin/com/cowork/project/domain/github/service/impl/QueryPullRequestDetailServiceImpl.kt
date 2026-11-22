package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.client.GithubPullRequestLiveStatus
import com.cowork.project.domain.github.entity.GithubPullRequestProjection
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestResDto
import com.cowork.project.domain.github.service.GithubAppCallExecutor
import com.cowork.project.domain.github.service.GithubPullRequestReader
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.GithubRepoRef
import com.cowork.project.domain.github.service.QueryPullRequestDetailService
import org.springframework.stereotype.Service
import team.themoment.sdk.exception.ExpectedException
import team.themoment.sdk.logging.logger.logger

/**
 * PR 상세는 projection으로 채우고, GitHub이 요청 시점마다 재계산해 projection으로 재구성할 수 없는
 * `mergeable`/`mergeableState`/`reviewDecision`만 `GithubAppClient`로 직접 조회해 합친다.
 * projection을 먼저 확인하므로, 동기화 전(503)이거나 없는 PR(404)이면 GitHub을 호출하지 않는다.
 *
 * 머지·닫힌 PR은 live 상태가 의미 없으므로 조회하지 않는다. 열린 PR의 live 상태 조회가 실패해도
 * 상세 조회 자체는 실패시키지 않고 live 필드를 null로 둔다.
 *
 * GitHub 응답(read-timeout 10초)을 기다리는 동안 DB 커넥션을 잡지 않도록 서비스 트랜잭션을 두지 않는다.
 * 권한·레포 해석과 projection 조회는 각자 짧은 읽기 트랜잭션에서 끝난다.
 */
@Service
class QueryPullRequestDetailServiceImpl(
    private val githubRepoAccessResolver: GithubRepoAccessResolver,
    private val githubPullRequestReader: GithubPullRequestReader,
    private val githubAppCallExecutor: GithubAppCallExecutor,
    private val githubAppClient: GithubAppClient,
) : QueryPullRequestDetailService {

    override fun execute(userId: Long, projectId: Long, repoId: Long, prNumber: Int): GithubPullRequestResDto {
        val repo = githubRepoAccessResolver.resolveForRead(userId, projectId, repoId)
        val pullRequest = githubPullRequestReader.findPullRequest(repo.owner, repo.repo, prNumber)
        val liveStatus = if (pullRequest.isOpen()) findLiveStatus(repo, prNumber) else null
        return GithubPullRequestResDto(
            number = pullRequest.number,
            title = pullRequest.title,
            body = pullRequest.body,
            author = pullRequest.author,
            state = pullRequest.state,
            mergeable = liveStatus?.mergeable,
            mergeableState = liveStatus?.mergeableState,
            reviewDecision = liveStatus?.reviewDecision,
            headRef = pullRequest.headRef,
            baseRef = pullRequest.baseRef,
            htmlUrl = pullRequest.htmlUrl,
        )
    }

    private fun GithubPullRequestProjection.isOpen() = state == OPEN_STATE && !merged

    private fun findLiveStatus(repo: GithubRepoRef, prNumber: Int): GithubPullRequestLiveStatus? = try {
        githubAppCallExecutor.execute { githubAppClient.getPullRequestLiveStatus(repo.owner, repo.repo, prNumber) }
    } catch (e: ExpectedException) {
        logger().warn(
            "Failed to fetch live status for pull request {}/{}#{} with {}: {}",
            repo.owner,
            repo.repo,
            prNumber,
            e.statusCode,
            e.message,
        )
        null
    }

    private companion object {
        const val OPEN_STATE = "open"
    }
}
