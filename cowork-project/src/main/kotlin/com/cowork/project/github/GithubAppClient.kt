package com.cowork.project.github

import com.cowork.project.dto.GithubApproveResultResDto
import com.cowork.project.dto.GithubMergeResultResDto
import com.cowork.project.dto.GithubPullRequestFileResDto
import com.cowork.project.dto.GithubPullRequestResDto
import com.cowork.project.dto.GithubPullRequestSummaryResDto
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
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
}
