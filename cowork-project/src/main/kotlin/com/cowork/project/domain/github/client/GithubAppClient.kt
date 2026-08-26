package com.cowork.project.domain.github.client

import com.cowork.project.domain.github.presentation.data.response.GithubCommentResDto
import com.cowork.project.domain.github.presentation.data.response.GithubIssueResDto
import com.cowork.project.domain.github.presentation.data.response.GithubLabelResDto
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestFileResDto
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestResDto
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestSummaryResDto
import com.cowork.project.domain.github.presentation.data.response.GithubRepoSummaryResDto
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam

/**
 * 이슈 생성 / PR 머지·승인은 Kafka(`GithubActionCommandPublisher`)로 전환되어 여기 없다.
 * 아래 이슈/라벨/댓글 관련 메서드는 cowork-github-app(2026-08 기준 `main`)에 대응하는 HTTP 라우트가
 * 아직 구현되어 있지 않다 — 호출 시 404가 날 수 있다. 실제 GitHub API 연동(Octokit) 코드 자체가
 * cowork-github-app에 없으므로, 단순 라우트 추가만으로는 못 고친다.
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

    @PostMapping("/api/repos/{owner}/{repo}/issues/{number}/comments")
    fun createIssueComment(
        @PathVariable owner: String,
        @PathVariable repo: String,
        @PathVariable number: Int,
        @RequestBody body: GithubAppCreateCommentReqDto,
    ): GithubCommentResDto

    @GetMapping("/api/repos/{owner}/{repo}/issues/comments/{commentId}")
    fun getIssueComment(
        @PathVariable owner: String,
        @PathVariable repo: String,
        @PathVariable commentId: Long,
    ): GithubCommentResDto

    @PatchMapping("/api/repos/{owner}/{repo}/issues/comments/{commentId}")
    fun updateIssueComment(
        @PathVariable owner: String,
        @PathVariable repo: String,
        @PathVariable commentId: Long,
        @RequestBody body: GithubAppUpdateCommentReqDto,
    ): GithubCommentResDto

    @DeleteMapping("/api/repos/{owner}/{repo}/issues/comments/{commentId}")
    fun deleteIssueComment(
        @PathVariable owner: String,
        @PathVariable repo: String,
        @PathVariable commentId: Long,
    )

    @GetMapping("/api/repos/{owner}/{repo}/labels")
    fun listLabels(@PathVariable owner: String, @PathVariable repo: String): List<GithubLabelResDto>

    @PatchMapping("/api/repos/{owner}/{repo}/issues/{number}/labels")
    fun updateIssueLabels(
        @PathVariable owner: String,
        @PathVariable repo: String,
        @PathVariable number: Int,
        @RequestBody body: GithubAppUpdateIssueLabelsReqDto,
    ): GithubIssueResDto
}
