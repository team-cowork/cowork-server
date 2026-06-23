package com.cowork.project.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "GitHub PR 머지 결과")
data class GithubMergeResultResDto(
    @field:Schema(description = "이미 머지되어 있던 PR에 대한 멱등 응답 여부")
    val alreadyMerged: Boolean,
    @field:Schema(description = "PR URL", example = "https://github.com/my-org/my-repo/pull/1")
    val prUrl: String,
    @field:Schema(description = "PR 번호", example = "1")
    val prNumber: Int,
)
