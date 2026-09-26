package com.cowork.project.domain.github.client

import com.cowork.project.domain.github.presentation.data.response.GithubCommentResDto
import com.cowork.project.domain.github.presentation.data.response.GithubIssueResDto
import com.cowork.project.domain.github.presentation.data.response.GithubLabelResDto
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestFileResDto
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestResDto
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestSummaryResDto
import com.cowork.project.domain.github.presentation.data.response.GithubRepoSummaryResDto
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam

/**
 * 이슈 생성 / PR 머지·승인은 Kafka(`GithubActionCommandPublisher`)로 전환되어 여기 없다.
 * 라벨 전체 교체·댓글 생성/수정/삭제도 Kafka(`GithubIssueWriteCommandPublisher`,
 * `github-app.issue-write.command`/`.result`)로 전환되어 여기 없다.
 * 아래 메서드들은 완전한 versioned event feed가 없는 GitHub 원본 조회이므로 request-scoped HTTP로
 * 남아 있으며, cowork-github-app에 대응하는 HTTP 라우트가 구현되어 있다.
 */
@FeignClient(
    name = "github-app",
    url = "\${github-app.service-url}",
    configuration = [GithubAppClientConfig::class],
)
interface GithubAppClient {

    @GetMapping("/api/orgs/{org}/repos")
    fun listOrgRepos(@PathVariable org: String): List<GithubRepoSummaryResDto>

    @GetMapping("/api/repos/{owner}/{repo}/pulls")
    fun listPullRequests(
        @PathVariable owner: String,
        @PathVariable repo: String,
        @RequestParam state: String,
    ): List<GithubPullRequestSummaryResDto>

    @GetMapping("/api/repos/{owner}/{repo}/pulls/{number}")
    fun getPullRequest(
        @PathVariable owner: String,
        @PathVariable repo: String,
        @PathVariable number: Int,
    ): GithubPullRequestResDto

    @GetMapping("/api/repos/{owner}/{repo}/pulls/{number}/files")
    fun listPullRequestFiles(
        @PathVariable owner: String,
        @PathVariable repo: String,
        @PathVariable number: Int,
    ): List<GithubPullRequestFileResDto>

    @GetMapping("/api/repos/{owner}/{repo}/issues")
    fun listIssues(
        @PathVariable owner: String,
        @PathVariable repo: String,
        @RequestParam state: String,
    ): List<GithubIssueResDto>

    @GetMapping("/api/repos/{owner}/{repo}/issues/{number}")
    fun getIssue(
        @PathVariable owner: String,
        @PathVariable repo: String,
        @PathVariable number: Int,
    ): GithubIssueResDto

    @GetMapping("/api/repos/{owner}/{repo}/issues/{number}/comments")
    fun listIssueComments(
        @PathVariable owner: String,
        @PathVariable repo: String,
        @PathVariable number: Int,
    ): List<GithubCommentResDto>

    @GetMapping("/api/repos/{owner}/{repo}/issues/comments/{commentId}")
    fun getIssueComment(
        @PathVariable owner: String,
        @PathVariable repo: String,
        @PathVariable commentId: Long,
    ): GithubCommentResDto

    @GetMapping("/api/repos/{owner}/{repo}/labels")
    fun listLabels(@PathVariable owner: String, @PathVariable repo: String): List<GithubLabelResDto>
}
