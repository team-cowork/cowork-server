package com.cowork.project.domain.github.client

import com.cowork.project.domain.github.presentation.data.response.GithubApproveResultResDto
import com.cowork.project.domain.github.presentation.data.response.GithubIssueResDto
import com.cowork.project.domain.github.presentation.data.response.GithubLabelResDto
import com.cowork.project.domain.github.presentation.data.response.GithubMergeResultResDto
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestFileResDto
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestResDto
import com.cowork.project.domain.github.presentation.data.response.GithubPullRequestSummaryResDto
import com.cowork.project.domain.github.presentation.data.response.GithubRepoSummaryResDto
import org.springframework.cloud.openfeign.FeignClient
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

    @GetMapping("/api/repos/{owner}/{repo}/labels")
    fun listLabels(@PathVariable owner: String, @PathVariable repo: String): List<GithubLabelResDto>

    @PostMapping("/api/repos/{owner}/{repo}/issues")
    fun createIssue(
        @PathVariable owner: String,
        @PathVariable repo: String,
        @RequestBody body: Map<String, Any?>,
    ): GithubIssueResDto

    @PatchMapping("/api/repos/{owner}/{repo}/issues/{number}/labels")
    fun updateIssueLabels(
        @PathVariable owner: String,
        @PathVariable repo: String,
        @PathVariable number: Int,
        @RequestBody body: Map<String, List<String>>,
    ): GithubIssueResDto
}
