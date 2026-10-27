package com.cowork.project.domain.github.client

import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable

/**
 * 이슈 생성 / PR 머지·승인은 Kafka(`GithubActionCommandPublisher`)로 전환되어 여기 없다.
 * 라벨 전체 교체·댓글 생성/수정/삭제도 Kafka(`GithubIssueWriteCommandPublisher`,
 * `github.issue-write.command.v1`/`.result.v1`)로 전환되어 여기 없다.
 * repo/PR/이슈/라벨/댓글 조회는 Kafka read-model projection으로 전환되었다. PR의
 * `mergeable`/`mergeableState`/`reviewDecision`은 GitHub이 요청 시점마다 재계산해 projection으로
 * 재구성할 수 없는 값이라, 이 세 필드만 request-scoped HTTP로 조회한다.
 */
@FeignClient(
    name = "cowork-github",
    url = "\${COWORK_GITHUB_URL:http://cowork-github:8090}",
    configuration = [GithubAppClientConfig::class],
)
interface GithubAppClient {
    @GetMapping("/api/repos/{owner}/{repo}/pulls/{number}/live-status")
    fun getPullRequestLiveStatus(
        @PathVariable owner: String,
        @PathVariable repo: String,
        @PathVariable number: Int,
    ): GithubPullRequestLiveStatus
}
