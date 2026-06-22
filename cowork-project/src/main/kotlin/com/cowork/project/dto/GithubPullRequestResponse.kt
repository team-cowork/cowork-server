package com.cowork.project.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "GitHub PR 상세")
data class GithubPullRequestResponse(
    val number: Int,
    val title: String,
    val body: String?,
    val author: String,
    val state: String,
    val mergeable: Boolean?,
    val mergeableState: String,
    @Schema(allowableValues = ["APPROVED", "CHANGES_REQUESTED"])
    val reviewDecision: String?,
    val headRef: String,
    val baseRef: String,
    val htmlUrl: String,
)
