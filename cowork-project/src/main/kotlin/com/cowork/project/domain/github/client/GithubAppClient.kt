package com.cowork.project.domain.github.client

import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestResDto
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable

/**
 * 이슈 생성 / PR 머지·승인은 Kafka(`GithubActionCommandPublisher`)로 전환되어 여기 없다.
 * 라벨 전체 교체·댓글 생성/수정/삭제도 Kafka(`GithubIssueWriteCommandPublisher`,
 * `github.issue-write.command.v1`/`.result.v1`)로 전환되어 여기 없다.
 * repo/PR/이슈/라벨/댓글 조회는 Kafka read-model projection으로 전환되었다. PR 상세의
 * `mergeable`/`mergeableState`/`reviewDecision`은 GitHub이 요청 시점마다 재계산해 projection으로
 * 재구성할 수 없는 값이라, PR 상세 조회만 request-scoped HTTP로 남아 있다.
 */
@FeignClient(
    name = "cowork-github",
    configuration = [GithubAppClientConfig::class],
)
interface GithubAppClient {
    @GetMapping("/api/repos/{owner}/{repo}/pulls/{number}")
    fun getPullRequest(
        @PathVariable owner: String,
        @PathVariable repo: String,
        @PathVariable number: Int,
    ): GithubPullRequestResDto
}
