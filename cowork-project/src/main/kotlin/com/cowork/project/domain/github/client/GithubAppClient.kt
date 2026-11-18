package com.cowork.project.domain.github.client

import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody

/**
 * 이슈 생성은 Kafka(`GithubActionCommandPublisher`)로 전환되어 여기 없다.
 * 라벨 전체 교체·댓글 생성/수정/삭제도 Kafka(`GithubIssueWriteCommandPublisher`,
 * `github.issue-write.command.v1`/`.result.v1`)로 전환되어 여기 없다.
 * repo/PR/이슈/라벨/댓글 조회는 Kafka read-model projection으로 전환되었다. PR의
 * `mergeable`/`mergeableState`/`reviewDecision`은 GitHub이 요청 시점마다 재계산해 projection으로
 * 재구성할 수 없는 값이라, 이 세 필드만 request-scoped HTTP로 조회한다.
 *
 * PR 머지·승인·닫기는 사용자가 요청한 GitHub 작업의 결과(성공, 충돌, 권한 없음, 본인 PR 승인 불가 등)를
 * 그 요청 안에서 돌려줘야 하는데, 이 결과는 durable state로 재구성할 수 없다. PR 상태는 GitHub이 소유하고,
 * projection은 나중에 webhook으로만 갱신되기 때문이다. 그래서 이 세 작업도 request-scoped HTTP로 호출한다.
 */
@FeignClient(
    name = "cowork-github",
    url = "\${COWORK_GITHUB_URL}",
    configuration = [GithubAppClientConfig::class],
)
interface GithubAppClient {
    @GetMapping("/api/repos/{owner}/{repo}/pulls/{number}/live-status")
    fun getPullRequestLiveStatus(
        @PathVariable owner: String,
        @PathVariable repo: String,
        @PathVariable number: Int,
    ): GithubPullRequestLiveStatus

    @PostMapping("/api/repos/{owner}/{repo}/pulls/{number}/merge")
    fun mergePullRequest(
        @PathVariable owner: String,
        @PathVariable repo: String,
        @PathVariable number: Int,
        @RequestBody request: GithubPullRequestActionRequest,
    ): GithubPullRequestActionResult

    @PostMapping("/api/repos/{owner}/{repo}/pulls/{number}/approve")
    fun approvePullRequest(
        @PathVariable owner: String,
        @PathVariable repo: String,
        @PathVariable number: Int,
        @RequestBody request: GithubPullRequestActionRequest,
    ): GithubPullRequestActionResult

    @PostMapping("/api/repos/{owner}/{repo}/pulls/{number}/close")
    fun closePullRequest(
        @PathVariable owner: String,
        @PathVariable repo: String,
        @PathVariable number: Int,
        @RequestBody request: GithubPullRequestActionRequest,
    ): GithubPullRequestActionResult
}
