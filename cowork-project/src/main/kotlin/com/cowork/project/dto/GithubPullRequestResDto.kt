package com.cowork.project.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "GitHub PR 상세")
data class GithubPullRequestResDto(
    @field:Schema(description = "PR 번호", example = "1")
    val number: Int,
    @field:Schema(description = "PR 제목", example = "feat: add login")
    val title: String,
    @field:Schema(description = "PR 본문")
    val body: String?,
    @field:Schema(description = "작성자 GitHub 사용자명", example = "octocat")
    val author: String,
    @field:Schema(description = "PR 상태", example = "open")
    val state: String,
    @field:Schema(description = "머지 가능 여부")
    val mergeable: Boolean?,
    @field:Schema(description = "머지 가능 상태", example = "clean")
    val mergeableState: String,
    @field:Schema(description = "리뷰 결정 상태", allowableValues = ["APPROVED", "CHANGES_REQUESTED"])
    val reviewDecision: String?,
    @field:Schema(description = "head 브랜치", example = "feature/login")
    val headRef: String,
    @field:Schema(description = "base 브랜치", example = "main")
    val baseRef: String,
    @field:Schema(description = "GitHub PR URL", example = "https://github.com/my-org/my-repo/pull/1")
    val htmlUrl: String,
)
