package com.cowork.project.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "GitHub PR 파일 변경")
data class GithubPullRequestFileResponse(
    val filename: String,
    val status: String,
    val additions: Int,
    val deletions: Int,
    val patch: String?,
)
