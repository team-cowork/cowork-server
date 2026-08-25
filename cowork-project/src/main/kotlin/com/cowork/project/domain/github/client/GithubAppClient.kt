package com.cowork.project.domain.github.client

import com.cowork.project.domain.github.presentation.data.response.GithubApproveResultResDto
import com.cowork.project.domain.github.presentation.data.response.GithubCommentResDto
import com.cowork.project.domain.github.presentation.data.response.GithubIssueResDto
import com.cowork.project.domain.github.presentation.data.response.GithubLabelResDto
import com.cowork.project.domain.github.presentation.data.response.GithubMergeResultResDto
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

    @PostMapping("/api/repos/{owner}/{repo}/pulls/{number}/merge")
    fun mergePullRequest(
        @PathVariable owner: String,
        @PathVariable repo: String,
        @PathVariable number: Int,
        @RequestBody body: Map<String, String>,
    ): GithubMergeResultResDto

    @PostMapping("/api/repos/{owner}/{repo}/pulls/{number}/approve")
    fun approvePullRequest(
        @PathVariable owner: String,
        @PathVariable repo: String,
        @PathVariable number: Int,
        @RequestBody body: Map<String, String>,
    ): GithubApproveResultResDto

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

    @PostMapping("/api/repos/{owner}/{repo}/issues")
    fun createIssue(
        @PathVariable owner: String,
        @PathVariable repo: String,
        @RequestBody body: GithubAppCreateIssueReqDto,
    ): GithubIssueResDto

    @PatchMapping("/api/repos/{owner}/{repo}/issues/{number}/labels")
    fun updateIssueLabels(
        @PathVariable owner: String,
        @PathVariable repo: String,
        @PathVariable number: Int,
        @RequestBody body: GithubAppUpdateIssueLabelsReqDto,
    ): GithubIssueResDto
}
