package com.cowork.project.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "GitHub PR 머지 결과")
data class GithubMergeResultResponse(
    @Schema(description = "이미 머지되어 있던 PR에 대한 멱등 응답 여부")
    val alreadyMerged: Boolean,
    val prUrl: String,
    val prNumber: Int,
)
